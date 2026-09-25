# Flight Deck Sentry

A small, single-purpose Android companion app for the **DJI RC Plus** smart controller. It runs in the background
while **DroneSense** flies the drone, and it **notifies**: a sound from the controller's own library, a vibration and a
heads-up banner when a crewed aircraft is predicted to come close to the drone, gets close, or enters a TFR/geofence
at the drone. The pilot then looks at AirSense / ForeFlight and acts. No map, no voice, never in DroneSense's way.

Version **0.4.2** (0.4.0, the owner's agreed plan of 2026-09-24; 0.4.1 renamed the replay to a neutral demo encounter;
0.4.2 adds the Resources readout, keeps PASSING banners PASSING, sounds every alert in Standard, stays armed
across restarts, and adds Share log).
Everything below describes 0.4.x; the short history at the end says what came before.

## Why it exists

In a real encounter recorded from public ADS-B data, Cirrus SR22T **N388KM** crossed a TFR and passed about 200 ft
below a DJI M4T drone at **0.24 nm**. Nothing warned the pilot: a ground ADS-B receiver's audio is easy to miss in
road noise, ForeFlight doesn't alert, and DroneSense shows no proximity warning. The public ADS-B feed also showed N388KM's
altitude as `"ground"` for the whole pass, at 160 kt. Sentry exists so this never happens silently again.

Replaying that recorded encounter through 0.4.0 (the demo drone DEMO-1 pinned as this controller's aircraft), the pilot gets:

| Replay time (PDT) | Tier | Sound | Banner (title · where · prediction · hint) |
|---|---|---|---|
| 11:51:50 | bound | bound aircraft | "Watching DEMO-1 Pilot, this controller's aircraft." |
| 11:52:14 | **TRACK** | track, popup | ▲ TRACK · N388KM Cirrus · SW 3.9 mi · 163 kt · ≈600 above · Passing within 1.0 mi in 1 min 19 s · Clear: move NW |
| 11:52:44 | TRACK | none (banner update) | … SW 2.5 mi · Passing within 0.9 mi in 50 s |
| 11:52:57 | **WARNING** | warning, popup | ⚠ WARNING · N388KM · SW 1.9 mi · 155 kt · ≈100 below · Closest 2,700 ft in 40 s · Clear: move NW |
| 11:53:03 · :14 · :20 · :26 · :32 · :38 | WARNING | short warning | the same banner, updated silently (1.6 mi … 1,300 ft; "Closest 1,300 ft in 1 s") |
| 11:53:23 | (TFR 0/0000 entry) | none: already a warning | logged: "Traffic entering TFR 0/0000: SW 4,700 ft · 160 kt · ≈300 below" |
| *11:53:40* | PASSING | passing | ● PASSING · N388KM · SE 1,200 ft · Diverging · closest was 1,200 ft *(the real closest approach, 0.24 nm)* |
| 11:53:41–11:54:53 | (warning → caution → advisory rings) | none | stays **● PASSING · N388KM · Diverging**, updated in place (0.4.2) |
| 11:54:54 | CLEAR | none | banner removed ("Outside 3.0 mi") |

The first sound is **86 s before the pass**, the warning **43 s before**. The same data with N388KM's altitude as the
public feed saw it (`"ground"` at 160 kt) gives the same alerts with "alt unknown" in place of the vertical figure:
unknown altitude counts as inside the protected volume. `DemoReplayTest` prints these timelines;
`DemoSelectionReplayTest` runs the full selection path, including the nothing-pinned case (cylinders around
DEMO-1's launch point).

**COLLISION RISK**, synthetic variant (`DemoReplayFixture.load(crossing = true)`, also in Settings → Replay): N388KM's
real track, the drone held level, and N388KM given a GPS altitude climbing 500 fpm through the drone's altitude
exactly at the pass. At 11:52:57 (43 s before the pass) the banner turns to **‼ COLLISION RISK · N388KM · SW 1.9 mi ·
155 kt · 400 below, climbing · 400 below, climbing through your altitude · 43 s · Clear: move NW ↑**, the alarm
tone repeats every 3 s with a long vibration until 11:53:42, then one PASSING sound, then clear.

## What the pilot gets

**Only notifications.** A sound, a vibration and a heads-up banner. Sentry never takes the foreground, never speaks,
never shows a map; the pilot looks at AirSense / ForeFlight.

### Geometry

- **Bound aircraft** = the pinned serial ("This controller's aircraft"), else the **controller cylinders**
  (unchanged since 0.3.3; see below).
- **Protected volume:** from the surface up to **+1,500 ft above the drone** (setting "Ceiling above aircraft").
  Traffic with unknown altitude counts as inside.
- **Rings** around the drone: advisory 3 nm, caution 1 nm, warning 0.5 nm (settings). They are **live even with the
  drone on the pad**, so nobody arms and takes off into busy airspace.
- **Targets shown** (list + compass): within 10 nm of the aircraft, else 15 nm of the controller, at or below
  18,000 ft. An alerting aircraft is always shown.

### Time to closest approach (the core)

Every second, per aircraft: straight-line extrapolation from its ADS-B position, ground track, ground speed and
vertical rate (`baro_rate` / `geom_rate` when reported; absent = level), against the drone's own velocity from its
last two fixes (slower than 1 kt = stationary). Sentry computes the **time to closest approach (tCPA)**, the
**horizontal miss** at that time, and the **vertical offset** then (both vertical rates). Aircraft altitude is
`alt_geom`, else `alt_baro` + 300 ft ("≈", estimated); `"ground"` at 50 kt or more is airborne, altitude unknown.

Optional **corridor widening** (setting, default **0°** = off): the miss thresholds grow by distance × tan(angle).

| Tier (highest wins) | Condition (defaults) |
|---|---|
| **COLLISION RISK** ‼ | tCPA ≤ **60 s** and miss ≤ **500 ft** and vertical at CPA ≤ **300 ft**; **or** inside 0.5 nm some time in the next 60 s while his vertical rate (with the drone's) carries him **through** the drone's altitude |
| **WARNING** ⚠ | tCPA ≤ **60 s** and miss ≤ **0.5 nm** and inside the volume at CPA; **or** actually inside the 0.5 nm ring and the volume |
| **CAUTION** ◆ | actually inside 1 nm and the volume |
| **TRACK ALERT** ▲ | tCPA ≤ **120 s** and miss ≤ **1 nm** and inside the volume at CPA |
| advisory △ | actually inside 3 nm and the volume |

The horizons 120 / 60 / 60 s with no widening are the **fleet simulation's** best option (86 real flights,
2026-09-23): 67 % fewer false alarms than 180 / 90 s with 5° widening, 39 % fewer alerts, warnings a median 69 s
before closest approach, and no missed true conflict. The owner's first plan (180 / 90 / 60 s, 5°) is only settings.

- Each escalation fires at once. A predicted tier holds **5 s** after the prediction leaves the corridor (while not
  diverging); a TRACK ALERT that lapses says "**no longer a factor**" (banner update, no sound).
- Hysteresis: rings +0.2 nm, ceiling +200 ft, predicted miss +0.2 nm and time +10 s once a tier is up. Stepping back
  up to a tier already alerted this pass within 15 s is not alerted again (anti-flap).
- **Zone entry** (TFR / geofence / cylinder): one alert when an aircraft inside the volume crosses in, **only for
  zones that contain the drone or lie within 2 nm of it** (setting). 0.3.5 alerted on any TFR in range: in the
  fleet simulation 100 of 101 zone alerts were a TFR 5–9 nm from the drone. An aircraft already at WARNING or
  above doesn't get a separate zone sound (its own alarm isn't interrupted); the entry is logged.
- Every traffic alert carries the fleet simulation's **closeness score** S = √((h / 2000 ft)² + (v / 500 ft)²),
  the worst per aircraft per pass, in the log (`S=0.79`), so field data can be scored the same way.

### Cadence (per aircraft; converging = range or tCPA decreasing)

| Tier | First | Repeats while converging | Repeat is |
|---|---|---|---|
| COLLISION RISK | full alarm + popup | every **3 s** until it stops being a risk | full alarm tone + long vibrate |
| WARNING | full sound + popup | ≥ 1 mi: 20 s · 0.5–1 mi: 12 s · < 0.5 mi or tCPA < 30 s: **6 s** (never faster) | short, softer sound + silent banner update |
| CAUTION | full sound + popup | every 20 s | short sound + silent banner update |
| TRACK ALERT | one sound + popup | banner update every 30 s | banner only |
| advisory | **one soft tone** (short, 60 % volume) + short vibrate + popup (0.4.2) | banner update every 30 s | banner only |

- **Diverging:** all repeats stop. One **PASSING** sound when the range opens after a WARNING / COLLISION RISK
  (silent after lower tiers), then **CLEAR** (no sound, banner removed) outside 3 nm or outside the volume.
- From PASSING to CLEAR the banner stays **● PASSING · <id> · Diverging** (grey, silent, updated in place), even
  while he is still inside the 0.5 nm ring (0.4.1 re-titled it "⚠ WARNING · Not closing"). Steps down are silent.
  Turning back toward the drone (range closing again) re-triggers by tier: full sound + popup (`PassingTitleTest`).
- **What makes a sound, by alert style** (0.4.2, owner decision: the pilot may be on an automated mission in
  DroneSense, not holding the controller, so a banner-only alert can be missed):

  | Alert | Standard (default) | Quiet | Loud |
  |---|---|---|---|
  | advisory (3 mi) entry | soft tone + short vibrate | banner only | full sound + vibrate |
  | advisory repeats | banner only | banner only | short sound |
  | TRACK ALERT | sound (repeats: banner) | sound (repeats: banner) | sound (repeats: banner) |
  | caution | sound + repeats | banner only | sound + repeats |
  | warning | sound + repeats | sound + repeats (intervals × 2) | sound + repeats |
  | COLLISION RISK | 3 s tone | 3 s tone (never slowed) | 3 s tone |
  | PASSING (after warning / collision) | sound | sound | sound |

  In Standard every traffic alert sounds. Quiet is 0.4.0–0.4.1's advisory behaviour (fleet simulation: 87 of 98
  false alarms under 0.3.5 were the 3 nm advisory ring alone) with every repeat interval doubled.

### Sounds, vibration, banners

- **Sounds** come from the controller's own library, one picker per level (advisory, track, caution, warning,
  collision risk, zone entry, passing, internet, bound aircraft, pre-flight), each with **Test**, **Default** and a
  0–100 % volume. Defaults (all present on the Android 10 image): Tejat, Canopus, Capella, **Oxygen** (warning),
  **Alarm_Buzzer** (collision risk), Spica, Deneb, Drip, Hojus, Voila. A sound that can't be found falls back to the
  default alarm (warning / collision) or notification sound, then to a generated beep: **never silence**.
- Warning and collision risk play on `USAGE_ALARM`, the rest on `USAGE_NOTIFICATION_EVENT`, with transient-duck
  audio focus for the length of the sound only. One sound at a time; a new one stops a longer one. At most **one
  sound per tick** (the most urgent). **No double-play:** Sentry plays the sound; every notification channel is
  silent and never vibrates. Verified on the emulator's audio capture over five runs: 60 sounds logged, 60 sound
  onsets recorded, each matching its sound's signature (plus 7 touch-sound clicks from adb taps), in
  `docs/audio-onsets-0.4.0.txt`.
- **Vibration** (`VibrationEffect`): track / advisory 150 ms; caution 150-100-150; warning 3 × 300 / 150;
  collision risk 500-200-500-200-500; housekeeping 100 ms. No vibrator → "not available on this controller" and it's
  skipped.
- **Banner** (heads-up), one per aircraft, updated in place, never stacked, fixed shape:

  ```
  ▲ TRACK · N388KM Cessna          ⚠ WARNING · N388KM              ‼ COLLISION RISK · N388KM
  SW 7.9 mi · 160 kt · 300 below, climbing
  Passing within 0.3 mi in 2 min 58 s    Closest 2,700 ft in 1 min 29 s        160 below, climbing through your altitude · 12 s
  Clear: move NW ↑
  ```

  "mi" is the pilot's miles, i.e. **nautical** miles (as ATC says "traffic, 3 miles"); under 1 mi distances are in
  feet. The hint is perpendicular to *his* track on the side the drone is already offset to (centred: away from his
  turn, else right), with ↑ / ↓ only when he is changing altitude toward the drone (below and climbing / crossing →
  ↑; above and descending → ↓). It never says more than "Clear:". On the heads-up the hint sits on the prediction's
  row (Android 10 gives a heads-up about 58 dp of text); expanded, each line is its own.
  Colours: track amber, warning and collision risk red, passing / clear grey. The countdown updates every second in
  place. It **pops up only on an escalation or a zone entry**; repeats update it silently. It clears itself after
  the **banner duration** (5 s, setting 2–30 s; each cadence refresh restarts it) and at once when the aircraft clears.
- **Banner actions:** **Got it** (that aircraft's repeats muted 60 s), **Ignore** (muted until it clears the rings;
  setting to offer it), **Quiet 5 min** (all traffic sounds off 5 min, banners still update, then a "Sentry sounds
  on" alert). Every mute gives way **at once** to an escalation, a COLLISION RISK, or the aircraft turning toward
  the drone (its predicted miss shrinking by ≥ 0.1 nm / 25 %, or converging again). A COLLISION RISK banner has no
  Got it / Ignore. A body tap only expands; it never opens the app. Every action is logged with its time and shown
  in Last alerts; mutes show in the target list ("MUTED 47 s").
- **Status notification** (the foreground service): "Bound to 1581F7K3C251F00C9B34 · DEMO-1 Pilot · 3 targets ·
  sounds on", plus "N388KM muted 47 s", "Quiet 4 min 12 s left" or "Internet offline". Actions **Disarm** and **Open
  Sentry**, the only way into the app from a notification.

### Housekeeping alerts (the only non-traffic sounds)

Internet lost / regained (ConnectivityManager plus any HTTP response from the worker, with a reachability probe
every 15 s and a 10 s hysteresis), bound aircraft acquired / lost / back, the pre-flight result, and "Sentry sounds
on" after Quiet. Tap = dismiss. Everything else (feed health, traffic stale, controller GPS, armed) is screen-only.

### Pre-flight check

The **Pre-flight** button checks: internet, fleet token (accepted), drone feed, bound aircraft present + on the pad /
airborne, controller GPS, traffic feed, TFR data, notifications enabled + heads-up allowed, battery-optimisation
exemption, sound (plays the warning sound; alarm / notification volume), vibration. Pass / fail with the fix for each
failure, and the result as a housekeeping alert.

### Low power

Bound aircraft on the pad or absent: cloud traffic and the drone feed every **5 s**; airborne: **2 s / 2 s**. The
Overwatch station link stays 1 s when enabled. The rates in use are shown in Sources ("Polling").

### Alert latency

The engine interpolates when each tier condition became true between two ticks; the log shows
"alert latency 0.3 s" from that moment to the sound starting. `LatencyTest` asserts < 2 s on every escalation of the
demo replays, by that estimate and against a 10 Hz run of the same data. On the emulator: 0.1–0.5 s.

## Main screen

Status header (armed / bound / waiting / the top alert) · **Sources** (feeds with ages, internet, poll rates, sounds,
vibration, **Resources**) · **Targets** (tier, CPA time and miss, MUTED countdown, zones) · **Last alerts** (with the sound played or
why not, and pilot actions) · compass rose · pinned bar **ARM / DISARM · Pre-flight · Settings**.

## Settings (in this order)

Rings & volume (+ targets shown) → controller cylinders → this controller's aircraft → prediction (120 / 60 / 60 s,
1 nm / 0.5 nm / 500 ft / 300 ft, corridor 0°) → cadence & alert style & banner duration & mute timings → sounds &
vibration → fleet token / Overwatch station / TFR zones / geofences / background (Resume armed after power-off) →
**Resources** → **Diagnostics (Share log)** → app update → replay. Settings save
themselves (0.3.4): about 0.4 s after typing stops; an invalid value turns red and the last valid one is kept. The
three prediction times must be ordered (track ≥ warning ≥ collision) and the track miss ≥ the warning miss.

## Armed stays armed (0.4.2)

Owner: Sentry stays armed until the pilot **DISARMs** it or **swipes it away** from the app switcher.

| What happens | Sentry does | The pilot sees / the log says |
|---|---|---|
| DISARM | stops, flight summary logged | "DISARMED" |
| Swiped away from Recents (armed or replaying) | disarms, cancels banners, releases the wake lock, stops; **never restarted** | "Disarmed by user (app closed)" |
| Crash or system kill (low memory) | the system restarts the service (`START_STICKY`); Sentry **re-arms** | housekeeping alert **"Sentry restarted"** (sound + vibrate + banner); log "Restarted after unexpected exit: re-armed" |
| Controller powered off and on while armed | `BOOT_COMPLETED` re-arms (setting **Resume armed after power-off**, default on) | housekeeping alert **"Sentry armed after restart"** |
| Powered off while disarmed / after a swipe-away | nothing at boot | log "not arming" |
| Force-stop (Settings → Apps → Force stop) | Android kills it and never restarts a force-stopped app; opening Sentry later leaves it DISARMED | log "Disarmed by user (app closed): … force-stopped?" |

The armed flag is saved on ARM and cleared on DISARM and swipe-away; that flag is what boot and restart read
(`core/RestartPolicy.kt`, `RestartPolicyTest`). Android 10 (the RC Plus) can't tell a force-stop from a crash after the
fact, so an armed flag found with no service running when the app is opened counts as the pilot's force-stop; a
controller power-cycled after a force-stop while armed re-arms at boot. Verified on the emulator
(`docs/lifecycle-0.4.2.txt`): kill -9 → restarted and re-armed in about 1 s with "Sentry restarted"; swipe-away →
disarmed, no restart within 45 s, no notifications left; force-stop → no restart in 60 s, opening the app →
disarmed; armed + reboot → armed after boot with "Sentry armed after restart"; disarmed + reboot → stays off.

## Resources (0.4.2)

Owner: "Is there any way to measure the compute power or how much resources the app is using from the controller?"
While armed, Sentry measures itself every 10 s and shows a **Resources** row at the bottom of the main screen's Sources
column (scroll it), for example:

```
Resources   CPU 0.97 % (3.9 % of one core) · 74 MB · batt — · 977 KB/min
```

How to read it (the row shows the last minute):

- **CPU 0.97 %** = the share of the whole controller (all its cores) Sentry used; **(3.9 % of one core)** is the same
  work as a share of a single core, the figure Android's developer tools show. Read from `/proc/self/stat` (user +
  system time) against the clock.
- **74 MB** = memory Sentry holds right now (PSS: its private memory plus its share of shared libraries).
- **−0.8 %/h** = how fast the battery is going down since ARM (shown after 5 min; "charging" while plugged in). This is
  the **whole controller's** drain (screen, DroneSense, radios included): Android gives an app no battery figure of its
  own. Compare a flight with Sentry armed to one without it.
- **977 KB/min** = Sentry's own network traffic (both directions) over the last minute.

**Settings → Resources** has the detail: CPU now / 1-min / 5-min / flight average / peak since ARM, memory now /
averages / peak and Java heap, battery used since ARM, network averages and total data since ARM, wake-lock time, and
what the measuring itself costs ("This monitor"). The log gets a line every 5 min (`RESOURCES …`) and a
**flight summary** at DISARM (`FLIGHT SUMMARY armed 23 min · CPU avg … · PSS peak … · battery used … · data …`),
both in **Share log**. A switch turns the measuring off.

Measured on the emulator (`docs/resources-0.4.2-armed-5min.txt`; AVD rc-plus-29, 4 cores, armed live on low-power
polling, UI never drawn, another app in front, four alternating 5-min runs measured from outside the app):

| | CPU, % of one core | of the 4 cores |
|---|---|---|
| monitor on (2 runs) | 1.21 / 1.18 (mean 1.20) | 0.30 |
| monitor off (2 runs) | 1.14 / 1.17 (mean 1.16) | 0.29 |
| the monitor itself (its own thread time) | 0.031 / 0.034 (3.1–3.4 ms per 10 s sample) | 0.008 |

So the monitor costs about **0.03–0.04 % of one core**, under the 0.1 % budget and inside run-to-run noise. Memory
(PSS) moved between 46 and 141 MB between runs whether the monitor was on or off: nearly all of it is native heap from
parsing the ~100 KB cloud ADS-B response every 5 s. Network: about **1 MB/min** received (~60 MB per armed hour) on
low-power polling, the cloud ADS-B feed; more when airborne (2 s polls). With Sentry's screen open and being scrolled
the CPU is several times higher (drawing the UI): in flight the screen is DroneSense's. The emulator's battery is
fixed, so battery drain still needs a real RC Plus.

## Coexistence with DroneSense

Owner: "make sure this software never impacts DroneSense on the controller while we're flying."

- **Closing Sentry disarms it** (see *Armed stays armed* below): swiping it away from Recents disarms and shuts it
  down cleanly; it is never restarted after that.
- **Never takes the foreground.** No activity from the service, no full-screen intents; banners are heads-up
  notifications. Verified 0.4.0: armed live for 5 minutes with another app in front, `mResumedActivity` was that app
  in 30 of 30 samples (`docs/coexistence-0.4.0-armed-5min.txt`).
- **Audio:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (never `GAIN`) for the length of one sound, released right after.
- **No prompts while armed.** Permission prompts only on the ARM tap before arming; the pre-flight list only on the
  pilot's own tap in Sentry. **Updates never install while armed.**
- **Measured:** see *Resources* below: 0.4.2 on the emulator, about 1.2 % of one core (0.3 % of the controller) armed.
- **Bounded work:** 1 s tick, 5 s watchdog, pollers at 1 / 2–5 / 2–5 s and TFRs every 10 min with capped backoff,
  one OkHttp client with 4/6/8 s timeouts. Measured 0.4.0 (rc-plus-29, armed live with low-power polling, another
  app in front, 5 min): **1.6 % of one core** (0.3.5: 3.3 %) and **PSS 116–124 MB** in a process that had drawn the
  UI (0.3.5: 138 MB); `docs/coexistence-0.4.0-armed-5min.txt`. The "lost" windows of the drone feed and cloud
  traffic follow the poll rate (20 s + two cycles), so a slow 5 s cycle doesn't flag a healthy feed.
- No USB, serial or DJI SDK access. Every poll and tick catches its own exceptions.

## Data sources (all run at the same time, never either/or)

The local feed is an **Overwatch ADS-B station**'s `/data/aircraft.json` (readsb-shaped) on the truck's Wi-Fi; without
one, all traffic comes from the online feed (the worker's `/api/live/adsb`) plus the drone's own AirSense contacts.

| What | Where | Cadence |
|---|---|---|
| Drone position | `GET {worker}/api/live/our-drones` and `/api/live/dronesense` + `X-Fleet-Token` | 2 s airborne, 5 s on the pad / absent |
| Traffic A: Overwatch ADS-B station (local) | `http://<station>:8080/data/aircraft.json` (readsb-shaped), typed URL and UDP beacon 41120 | 1 s |
| Traffic B: cloud | `GET {worker}/api/live/adsb` (browser User-Agent), filtered to 30 nm | 2 s airborne, 5 s on the pad / absent |
| Traffic C: AirSense | the drone's own ADS-B contacts relayed in `our-drones` | with the drone feed |
| TFRs | `GET {worker}/api/tfrs`, cached on disk | 10 min |
| Geofences | Settings: circle + GeoJSON import | — |
| Internet | ConnectivityManager + any worker response; probe `GET {worker}/` when quiet for 15 s | 15 s |

`{worker}` defaults to `https://uas-app.drobinson911.workers.dev`. Traffic is merged per ICAO hex; the fresher
position wins. Ages come from each feed's relative fields, so a wrong clock can't make stale data look fresh.

## The DJI MSDK constraint

DJI's Mobile SDK allows **one** app to own the aircraft link, and DroneSense owns it. Sentry has **no DJI dependency**
and never talks to the aircraft; it gets the drone's position from the network.

## Which aircraft Sentry protects

- **Settings → This controller's aircraft:** type the airframe serial once, or tap one of the aircraft in the feed
  now. It is kept until cleared. **No other drone is ever watched.**
- Pinned and in the feed (airborne, else on the pad): that aircraft. Pinned but absent: "WAITING FOR THIS
  CONTROLLER'S AIRCRAFT · <serial>", and the controller cylinders protect the pilot meanwhile. Nothing pinned: the
  cylinders only.
- Drop-out: 15 s without a position → "Drone position lost" (bound-aircraft alert); 30 s → the cylinders.

## Controller protection cylinders

Any number, each with a name, radius (nm or ft), floor (default SFC) and ceiling in ft above the controller (default)
or ft MSL. Defaults: "ops area" 1 nm SFC–1,500 ft and "advisory area" 3 nm SFC–3,000 ft. Used while the pinned
aircraft is not in the feed, or when none is pinned: inside a cylinder = CAUTION (with a zone-entry alert); TRACK and
WARNING predictions are about the controller, with a cylinder's floor/ceiling as the vertical test (no COLLISION
RISK: the controller isn't flying). Elevation: Settings override, else Android's MSL altitude (API 34+), else raw GPS
("≈ellipsoid").

## Install on the RC Plus

1. Get the APK (always the newest release, signed with the Sentry release key):
   **https://github.com/drobinson911/flight-deck-sentry/releases/latest/download/flight-deck-sentry.apk**
2. Install it (allow "install unknown apps" when Android asks), or `adb install -r flight-deck-sentry.apk`.
   Coming from 0.2.0 or older: uninstall once first (different signing key).
3. Open Sentry → **Settings**: paste the fleet token, set **This controller's aircraft**, check the cylinders, and
   pick sounds if you like (**Test** each).
4. Tap **ARM**, allow notifications and location, and accept the battery-optimisation exemption (Settings).
5. Tap **Pre-flight**. Fix anything red. Then switch to DroneSense: Sentry's banners appear over it.

**Screen:** the RC Plus runs at density 2.5 (~768 × 480 dp). The action bar is pinned; every column scrolls.

## Updating

Sentry updates itself from the public GitHub releases, **never without your tap and never while armed**. Settings →
App update shows the installed and latest versions and the release notes; **Download and install** verifies the APK
and opens Android's installer. It checks at most once a day (hourly after a failure); a newer release posts one silent
notification.

## Share log (0.4.2)

**Settings → Diagnostics → Share log** writes the last 24 hours to a text file and opens Android's share sheet (email,
Bluetooth, Drive, whatever the controller has); **Copy** puts it on the clipboard instead (the newest ~200 KB) for
controllers with nothing to share to. It holds every alert (time, tier, sound, banner text), pilot actions and
mutes, source health changes, pre-flight results, resource summaries, restarts, plus the app version and the
controller's model, Android version and screen (px, dpi, dp, font scale). Drone callsigns and names, the fleet token,
and server / station addresses are replaced before it leaves the controller; the bound serial is kept. The log is kept
in the app's own storage, trimmed to 24 h (`core/ShareLog.kt`, `ShareLogTest`).

## Releasing (maintainers)

`VERSION` is the one version source (`0.4.0` → versionCode 400). Bump it, commit, `git tag v0.4.0 && git push origin
main v0.4.0`. `release.yml` checks tag == VERSION, runs the tests, builds `assembleRelease` with the repository
secrets, verifies the release certificate (SHA-256 `07d612bf02fcdfcc3a617d091909bb83d3e44cfae7e58f34bd146b6a75cc51dc`)
and attaches `flight-deck-sentry.apk` + `.sha256`. Pilot release notes come from `docs/release-notes/<tag>.md`. The
keystore is never in the repo (its location is in the maintainer's private notes).

## Replay mode

Settings → **Replay: demo encounter** plays the recorded drone track (as DEMO-1), N388KM's track and the TFR (as 0/0000) through the same
selector, engine, sounds and banners used live (1× / 4×; optional public-feed view; optional **synthetic crossing
variant** for COLLISION RISK). Debug builds over adb:

```
adb shell "am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action set --es pinned 1581F7K3C251F00C9B34"
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 1 [--ez crossing true] [--ez cloud_view true]
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action preflight
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action got_it --es hex a479ef --es id N388KM   # also: ignore, quiet
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action set --es style QUIET               # STANDARD | QUIET | LOUD
# with another app in front (as root): am start-foreground-service -n com.uasflightdeck.sentry/.SentryService -a com.uasflightdeck.sentry.REPLAY --ez crossing true
```

The replay drone's serial `1581F7K3C251F00C9B34` is **synthetic** (the recorded track has none).

## Build & test

```
./gradlew testDebugUnitTest   # :core:test (189) + app tests (17): prediction, tiers, cadence, mutes, banner text + hint,
                              # PASSING hold + turn-back, resources (/proc parsing, averaging, battery, texts),
                              # restart / boot policy, share-log formatter + redaction, durations in words,
                              # outputs (sound fallback, vibration, one sound per tick), connectivity, poll rates,
                              # pre-flight, latency, demo timelines incl. the crossing variant, selection, parsers
./gradlew assembleDebug
./gradlew assembleRelease     # needs the release key
```

JDK 17, compileSdk 34, minSdk 26, targetSdk 33. APK: 0.3.5 release 7.50 MB → 0.4.0 release 5.50 MB (the voice bank
is gone).

## Screenshots (AVD `rc-plus-29`: 1920×1200 at 400 dpi, Android 10, the real controller's profile)

0.4.2: **81** the Resources row (main screen, Sources column; armed, UI open) · **82** Settings → Resources
detail (while being scrolled, so CPU is high) · **83** Settings → Diagnostics (Share log / Copy) · **84** the share sheet
(emulator; the RC Plus lists its own apps).
0.4.0: **69** TRACK banner over another app with Got it · Ignore · Quiet 5 min · **70** the WARNING banner · **71**
COLLISION RISK (synthetic crossing replay; no Got it / Ignore) · **72** replay with N388KM muted by Got it ("MUTED
56 s", the action in Last alerts) · **73** armed live, waiting for this controller's aircraft · **74** the status
notification with Disarm / Open Sentry · **75** the pre-flight result (emulator: no GPS fix, replay serial not in the
feed) · **76–77** Settings → Prediction · **78** Cadence & alert style · **79** Sounds & vibration · **80** the sound
picker (the emulator's picker is Google's Sounds app; the RC Plus shows Android's own). **72** was retaken on 0.4.1
(demo replay). The remaining earlier screenshots (01–68) belong to 0.1–0.3.5 and are kept for history; the ones
that showed the old replay names or live fleet callsigns were removed.

## History

0.1–0.2: rings, predictive CPA and TFR alerts, spoken with text-to-speech; drone selection. 0.3: pinned
serial, self-update. 0.3.3: one bound aircraft, surface-to-ceiling volume, DroneSense coexistence. 0.3.4: Settings
auto-save. 0.3.5: the RC Plus turned out to have no text-to-speech engine, so Sentry shipped its own recorded voice.
**0.4.0 removes the voice entirely** (owner decision): spoken callouts were slow to parse over rotor and road noise
and competed with the radio; a distinct sound + a glanceable banner, then a look at AirSense / ForeFlight, is faster.
The prediction model, tiers and cadence were redesigned with the owner and tuned on a fleet simulation of 86 real
flights. 0.4.1 renames the bundled replay to a neutral demo encounter (placeholder drone, callsign and TFR id; the
recorded geometry and timing are unchanged). 0.4.2: Resources readout, PASSING banners stay PASSING, every alert sounds
in Standard, durations in words ("1 min 28 s"), armed stays armed across crashes and power cycles (swipe-away
disarms), Share log, "Overwatch station" naming. See `docs/DESIGN.md`.

## Layout

```
core/  pure Kotlin/JVM (no Android): Prediction (tCPA, miss, vertical, corridor, crossing test), AlertEngine (tiers,
       holds, hysteresis, zones, cadence), Cadence, Banner (fixed-shape text + hint), Outputs (SoundLevel, SoundChoice,
       OutputPlanner, Playback), Mutes (MuteBook), Connectivity (InternetMonitor), PollRates, Preflight, AlertLatency,
       Geo/CpaMath, Parsers, Selection (DroneSelector, Cylinder), TargetDisplay, FleetStatus, HealthMonitor, Replay,
       Update, Resources (ProcStat, ResourceMeter, ResourceText), RestartPolicy, ShareLog (+ tests)
app/   SentryService (FGS, pollers, watchdog, replay, output pipeline, pre-flight), SoundPlayer (sounds, vibration,
       focus), Notifier (traffic / housekeeping / status notifications, banner timers), MainActivity, SettingsActivity,
       Settings, FieldRules, Feeds (Http with reachability), RadarView, Updater, DroneHistory, ScreenLayout,
       ResourceMonitor (10 s sampler), LogStore (24 h log file for Share log), BootReceiver (resume armed)
docs/  DESIGN.md, release notes, screenshots, replay / coexistence / resources / lifecycle logs
```
