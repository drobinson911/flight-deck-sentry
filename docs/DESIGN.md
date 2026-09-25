# Flight Deck Sentry: design (0.4.0)

0.4.0 implements the plan agreed with the owner on 2026-09-24, with the coordinator's changes from the fleet
simulation of 86 real flights (2026-09-23). This file records how it works and why, and every place where the plan
had to be interpreted. The README is the pilot-facing description.

## Purpose and limits

Sentry is a background companion on the DJI RC Plus while DroneSense flies. It **only notifies** (sound + vibration +
heads-up banner); the pilot then looks at AirSense / ForeFlight and acts. No map, no voice. It must never impact
DroneSense: it never takes the foreground while armed, ducks other audio only for the length of a sound, never
prompts while armed, polls at bounded rates, and the updater waits for disarm.

## Architecture

```
feeds (pollers, 1/2/5 s + TFR 10 min)  ──►  SentryService tick (1 s, live or replay clock)
                                              │
DroneSelector (pinned serial, else controller)│
AlertEngine.step ──► TargetViews + AlertEvents (tier, phase, cue, popup, banner text, crossedAt, S)
HealthMonitor / InternetMonitor ──► screen-only / housekeeping events
MuteBook.step (expire, give way)             │
OutputPlanner.plan ──► per event: sound level + cue (after mutes, style, one sound per tick), banner action
                                              │
SoundPlayer (sound + vibration)   Notifier (traffic / housekeeping / status notifications, timers)   SentryBus (UI)
```

Everything that decides *whether and how* the pilot is alerted is pure Kotlin in `core/` and unit-tested on the JVM:
`Prediction`, `AlertEngine`, `Cadence`, `Banner`, `Outputs` (`SoundLevel`, `SoundChoice`, `OutputPlanner`,
`Playback`), `Mutes`, `Connectivity`, `PollRates`, `Preflight`, `AlertLatency`. The app only executes the plan.

## Prediction (`core/Prediction.kt`)

Straight line, relative frame: `rel` = aircraft − drone (local east/north, m), `vRel` = aircraft − drone velocity.
tCPA = −(rel·vRel)/|vRel|² (0 when not converging), miss = |rel + vRel·tCPA|, vertical at CPA = dv + (vs_aircraft −
vs_drone)·tCPA. Aircraft velocity: reported track + ground speed, else derived from the last two positions (N388KM's
public feed had no track). Aircraft vertical rate: `baro_rate`, else `geom_rate`, else **level** (a rate derived from
jittery altitudes would fake crossings). Drone velocity and vertical rate: from its last two fixes; < 1 kt =
stationary (GPS jitter must not make parked traffic "converge", tested).

Corridor widening, `threshold + distance × tan(angle)`, is implemented and tested but **defaults to 0°** (fleet
simulation: widening made 180/90 noisier, 150 false alarms vs 98).

**Crossing test (COLLISION RISK, second arm):** solve |rel + vRel·t| ≤ 0.5 nm for the window [t1, t2] ∩ [0, 60 s];
the altitude crossing time tc = −dv / vs_rel must fall inside it, with tc ≥ 0 (moving apart vertically never counts).
Both vertical rates are used, so a drone climbing through level traffic counts too (tested).

## Tiers (`AlertEngine`)

Order, lowest to highest: advisory < **TRACK** < caution < WARNING < COLLISION RISK. *Interpretation:* the plan lists
TRACK, WARNING, COLLISION RISK as the prediction tiers and advisory / caution as ring tiers without ordering them
against TRACK. TRACK sits above advisory (a predicted conflict outranks being 3 nm away, and the ring entry under a
TRACK ALERT is then silent, as the cadence table intends) and below caution (an aircraft on a TRACK ALERT that then
gets inside 1 nm still escalates and sounds).

- Volume: surface … ceiling above the drone (+200 ft once alerting). TRACK and predicted WARNING use the vertical
  offset **at CPA**; the rings use the offset now. Unknown altitude is inside everywhere, including COLLISION RISK's
  "≤ 300 ft" (fail wide; the public-feed demo view never reaches it because its miss is 1,200 ft).
- Holds: a predicted tier stays up while its prediction has been out of the corridor for ≤ 5 s, and never while
  diverging. Hysteresis once a tier is up: rings +0.2 nm, predicted miss +0.2 nm, prediction time +10 s. The
  hysteresis was added after the first demo run flapped TRACK / clear / TRACK at 11:52:14–39 (derived velocity
  noise); with it the TRACK ALERT is one sound then 30 s banner updates.
- Anti-flap: stepping back up to a tier already alerted this pass within 15 s is a silent update (the COLLISION RISK
  tone keeps its 3 s rhythm). Seen in the crossing replay at 11:53:04–06.
- "No longer a factor": a TRACK ALERT that lapses (still converging) becomes a silent banner update, even if the
  aircraft is inside a ring.
- Zone entry: only zones containing the drone or within `zoneAlertNm` (2 nm) of it, and only for aircraft inside the
  volume (TFRs / geofences; cylinders have their own band). If the aircraft is already at WARNING or above, or an
  escalation fires in the same tick, the zone entry is logged only: it must not interrupt the alarm cadence.
- Controller mode (no bound aircraft): cylinders replace the rings (inside = CAUTION); TRACK / WARNING predictions
  about the controller with the cylinders' bands; no COLLISION RISK.
- Closeness score S = √((h/2000)² + (v/500)²), worst per pass, on every traffic event (fleet-simulation scoring).

## Cadence (`Cadence`, engine)

Per aircraft, repeats only while converging (range or tCPA decreasing; `tCPA > 0` and the range rate is not
opening), except the COLLISION RISK tone, which runs until the tier drops. WARNING bands 20 / 12 / 6 s with a 6 s floor;
caution 20 s (short sound); TRACK and advisory 30 s banner updates. Quiet doubles all intervals except the collision
tone. PASSING fires once when the range opens after the aircraft was seen converging; it sounds only after a WARNING
or COLLISION RISK. CLEAR is silent and removes the banner. After a pass, only a real re-convergence (range rate
closing) re-triggers.

## Output (`OutputPlanner`, `SoundPlayer`, `Notifier`)

- **One sound per tick**, the most urgent (collision > warning > zone > caution > track > passing > advisory >
  housekeeping). Every banner still posts.
- Mutes apply to REPEAT / UPDATE / PASSING sounds only. Escalations, zone entries and COLLISION RISK always sound
  and drop per-aircraft mutes. *Interpretation:* "all mutes return instantly on escalation" is applied to Quiet
  5 min as "an escalation of any aircraft sounds through Quiet" without ending Quiet, so Quiet still silences the
  repeats of everything else in busy airspace.
- Advisory is banner-only unless the style is **Loud** (fleet simulation: 87 of 98 false alarms were the advisory
  ring alone). Caution is banner-only in Quiet.
- **Sounds** by file name from the device library (`MediaStore.Audio.Media.INTERNAL_CONTENT_URI`, `DISPLAY_NAME`):
  URIs differ per device, names are stable. The defaults were chosen on the API 29 image for distinctness and a single
  onset each (Alarm_Beep_03 has two beeps, so the warning default is Oxygen). Resolution chain: picked → level
  default → default alarm (warning / collision) → default notification → `ToneGenerator` beep. Never silence.
- FULL cue: the sound, capped at 2.5 s. SHORT cue (repeats): 60 % volume, cut at 0.7 s.
- **No double-play:** all channels are created with no sound and no vibration (the 0.3.x "alerts" channel, which
  vibrated, is deleted). Proven on the emulator's audio capture: 60 sounds logged, 60 sound onsets in the WAV.

### Banners: what Android 10 actually does

- `BigTextStyle` in a **heads-up** collapses newlines into spaces (seen on the emulator), so the four lines are a
  custom `RemoteViews` layout with `DecoratedCustomViewStyle` (the system adds header and actions). A decorated
  heads-up gets ≈58 dp of content with the action row, so the heads-up has three rows (title / where / prediction +
  hint right-aligned); expanded, four. COLLISION RISK (no actions) has the room anyway.
- In-place updates use `setOnlyAlertOnce(true)` on the HIGH channel: SystemUI updates a showing heads-up without
  re-alerting. A cadence repeat for a banner that has already timed out is re-posted with `setSilent(true)` (the
  group-alert trick), which never pops up. An escalation re-posts without only-alert-once, which pops it up again.
- Timers: each popup and each cadence refresh restarts the banner-duration timer; the per-second countdown refresh
  does not and never re-posts a cancelled banner. CLEAR cancels at once.
- Banner colours are darker variants (amber #B26A00, red #D32F2F, grey) readable on the light notification background.
- Actions are `PendingIntent.getForegroundService` to the service (never an activity). The status notification has
  no content intent: **Open Sentry** is the only way in.

## Housekeeping and connectivity

`InternetMonitor`: observed state from (network has INTERNET capability) + (any HTTP response from the worker within
20 s; a 401/404 still proves the internet) + (last network-level failure newer than the last response). The observed
state must hold 10 s before it is adopted; start-up ONLINE is silent, start-up OFFLINE is alerted. The service probes
`GET {worker}/` only when nothing answered for 15 s. Housekeeping alerts: internet lost / back, bound aircraft
acquired ("Watching …") / lost (first "Drone position lost" only; the "Waiting …" drop-out 15 s later is screen-only)
/ back, pre-flight result, "Sentry sounds on".

## Low power and latency

`PollRates.of(boundAirborne)`: airborne 2 / 2 s, on the pad or absent 5 / 5 s, station 1 s. Pollers read the rate each
cycle. Latency: `AlertEvent.crossedAtMs` is interpolated from each tier's margin between the two ticks around the
crossing; the service logs detection + (sound start − tick) as "alert latency x.x s". `LatencyTest` checks both that
and the 1 s engine against a 10 Hz engine on the same data (< 2 s including a 250 ms sound-start budget).

## Pre-flight

`Preflight.items(facts)` is pure (tested); the service gathers the facts with one-shot fetches, so it works armed or
not, plays the warning sound as the audibility test, and posts the summary as a housekeeping alert. A missing
vibrator is reported, not failed.

## Settings

New keys: prediction (`trackSec`, `warningSec`, `collisionSec`, misses, `corridorDeg`), `zoneAlertNm`, `alertStyle`,
cadence intervals, `bannerSec`, `gotItSec`, `quietMin`, `ignoreEnabled`, per-level `sound_<key>` / `vol_<key>`,
`vibrationOn`, `replayCrossing`. One-time migration (`schema` 400): a stored ceiling of exactly 2,000 ft (the old
default, stored by 0.3.4's auto-save) becomes 1,500; the voice keys are removed. A ceiling the pilot chose is kept.
Invalid prediction ordering falls back to the defaults in the engine config, and the Settings screen refuses to store it.

## Kept from 0.3.x

Selection (pinned serial else controller cylinders, no other drone ever), the 400 dpi screen rules (pinned action
bar, every column scrolls), Settings auto-save, stacked feeds with independent backoff and a watchdog, UI derived from
live state every tick, the JSON depth guard, self-update from GitHub releases (never while armed).

## Verification (0.4.0)

- `./gradlew testDebugUnitTest`: 157 core + 17 app = 174 tests, exit 0.
- Emulator `rc-plus-29` with audio captured to WAV (`docs/audio-onsets-0.4.0.txt`): 60 sounds logged over five runs,
  60 sound onsets, one per sound (the pinned replay 10/10, the crossing replay 19/19 twice with the collision tones
  3.0 s apart, Got it run 10/10, pre-flight 2/2), plus 7 touch-sound clicks from the adb taps. Repeats are the
  softer variant (warning full −15 dB / short −20 dB). Banners over another app, Got it → mute → instant return when
  the miss shrank ("Sounds back for N388KM: turning toward the drone"), pre-flight, status notification, and a
  5-minute armed coexistence run: 0 of 30 samples with Sentry resumed, 1.6 % of one core, PSS 116–124 MB
  (`docs/coexistence-0.4.0-armed-5min.txt`). Logs: `docs/replay-logcat-0.4.0-*.txt`, `docs/preflight-and-live-0.4.0.txt`.
- The health "lost" windows follow the poll rate (20 s + two cycles): on the emulator a fixed 15 s window flagged the
  drone feed lost between two good 5 s low-power polls.

## Not done / out of scope

- The real RC Plus's sound library is unknown: the defaults are by name with fallbacks; the pre-flight Sound check
  and each Settings Test button are the way to confirm on the controller.
- The emulator has no GPS fix and a vibrator only as far as `hasVibrator()` reports; vibration patterns are unit-tested,
  not felt.
- The emulator's sound picker is Google's Sounds app; the RC Plus (no Google services) shows Android's own picker.

## History

0.1–0.2 spoke with text-to-speech; 0.3.5 bundled a recorded voice because the RC Plus has no TTS engine. 0.4.0
removes voice by owner decision (a distinct sound + a glanceable banner, then a look at AirSense / ForeFlight, is
faster to act on over rotor and road noise, and doesn't compete with the radio). The voice bank, its generator
(`tools/voicebank`) and the grammar tests are deleted; the sentence formatting survives as the banner text.
