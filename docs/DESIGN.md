# Flight Deck Sentry: design decisions

Written 2026-09-23 alongside v0.1.0. Each entry gives the decision, then the reason for it.

## Architecture

- **Two modules.** `:core` is pure Kotlin/JVM with no Android or DJI dependencies. It holds
  geodesy, CPA, the alert engine, source health, every feed parser, and the replay loader.
  `:app` is the Android shell. Because the logic that decides what the pilot hears is in
  `:core`, it is unit-tested on the JVM against the real demo data. `testDebugUnitTest`
  in `:app` depends on `:core:test`, so the documented gate cannot pass without running the
  engine tests.
- **No DJI SDK.** DroneSense owns the MSDK link, and only one app can hold it. The drone's
  position comes from the fleet network feeds.
- **Views, not Compose.** This keeps the build small and fast on the controller. The UI is
  a handful of text panels plus a Canvas radar.
- **SharedPreferences, not DataStore.** Settings are synchronous and tiny, and the service
  re-reads them every tick, so a change applies within 1 s with no restart or plumbing.
- **AGP 8.5.2 / Kotlin 1.9.24** are the same pins as flightdeck-air. The Gradle wrapper uses
  8.14.3, which is already in the local Gradle cache. `buildToolsVersion 35.0.0` is used
  because 34.0.0 isn't installed here.

## Redundancy (stacked, never either/or)

- **Ownship:** Sentry polls our-drones (Flight Deck Air) **and** the DroneSense snapshot
  every 2 s; the `DroneSelector` picks the drone by callsign pattern, then serial (see
  "Drone selection" below). With no drone selected, Sentry protects cylinders around the
  controller's own GPS, and every switch is spoken. (v0.1's manual pin was removed in v0.2:
  the owner chose controller-centred protection as the only fallback.)
  Why both fleet feeds: while DroneSense flies the drone, Flight Deck Air *can't* run
  (MSDK), so the DroneSense snapshot is the feed that will normally carry the drone. This
  was confirmed live on 2026-09-23: DEMO-2 showed up via `/api/live/dronesense` while
  our-drones was empty.
- **Traffic:** the truck station, cloud ADS-B, and the drone's own AirSense contacts
  (when Flight Deck Air relays them) are merged per hex. The newest position wins, and
  identity fields are filled in from whichever feed has them.
- **Station address:** a typed URL **and** Overwatch's UDP beacon (41120). Each candidate
  is tried in turn every poll.
- **Pollers:** each has its own coroutine, exponential backoff (capped: station 10 s, fleet
  15 s, cloud 30 s), and a heartbeat. A **watchdog** runs every 5 s and relaunches any
  poller whose heartbeat is older than interval + max backoff + 20 s. It also restarts the
  1 s tick loop if it stalls for more than 10 s, and re-acquires the wake lock.
- **Process:** foreground service (dataSync|location) with a partial wake lock and
  `START_STICKY`; after a sticky restart it re-arms from the saved "armed" flag. Re-arming
  after boot or an app update is opt-in. Sentry asks for a battery-optimisation exemption
  when it is first armed.
- **Voice:** if TTS init fails, it retries with backoff. If TTS stays unavailable, the tones
  still play and the heads-up banners still post, and the UI shows VOICE UNAVAILABLE in red.

## Truthfulness (the UI never shows a state that isn't true)

- The engine's `StepResult.targets` is recomputed from live inputs every second. The UI
  renders only that, never "the last alert".
- The UI re-renders every second. If the service's tick timestamp is more than 3 s old,
  the banner turns red ("SENTRY NOT RUNNING — engine stalled") instead of freezing on a
  green state.
- Each source row shows its state, calculated at render time from its last *successful*
  fetch, next to its age in seconds. A disk-cached TFR set reports the file's age, not
  "now".
- Ownship age comes from the feed's own relative age field (`_ageMs`) subtracted from our
  receive time. The only exception is DroneSense: its `lastUpdate` is an absolute time, so
  we trust the controller's clock (NTP/GPS) for it. Traffic ages use `seen_pos` the same
  relative way, so a truck laptop with a wrong clock can't make stale data look fresh.
- The controller's elevation is the Settings override, else Android's MSL altitude (API
  34+), else the raw GPS altitude, which is WGS-84 ellipsoid height (about 100 ft off MSL in
  California). The Ctrl GPS row says which ("elev set" / "MSL" / "≈ellipsoid").
- Selection mode and controller-GPS age are recomputed every tick from the selector's
  result; the fix age is taken from the fix's own timestamp.

## Alert-engine choices (beyond the spec)

- **Treat `"ground"` at 50 kt or more as airborne with unknown altitude.** N388KM's
  transponder was in ground mode from 11:50:42 to 11:54:42, and the public feed showed
  `alt_baro:"ground"` at 160 kt for the entire pass. A naive "drop ground traffic" filter
  would have stayed **silent**. A dedicated test replays that view, and a mutation check
  confirmed the test fails without this rule.
- **Dead reckoning.** A target is projected forward from its last report along its
  velocity, for at most 10 s. This compensates for ADS-B latency and puts the TFR entry at
  11:53:23 instead of the next report at 11:53:27.
- **Velocity from history.** When `track` is missing (it was null for N388KM throughout),
  velocity is taken from the last two positions (0.5 to 30 s apart). The drone's own
  velocity also comes from its history and is used in the relative motion for CPA.
- **Hysteresis.** A ring has to be exceeded by 0.2 nm, and the vertical band by 200 ft,
  before the severity drops. This stops a target on a ring edge from flapping.
- **De-escalation is silent.** The re-announce interval then applies at the new level.
  Targets that are **diverging** aren't re-announced; they get their "clear" when they
  leave the rings. This keeps a departing aircraft from generating a string of callouts.
- **Combined callouts.** When a TFR entry and a proximity alert for the same aircraft fire
  on the same tick, they become one callout. The TFR sentence already carries direction,
  distance, vertical and trend, and takes the higher severity (at least caution).
- **"On the ground" instead of "track lost".** An announced target that switches to
  ground at low speed gets "X on the ground." This was found during live testing, when a
  landing DAL756 at SFO was reported as "track lost". A target that simply disappears gets
  "X track lost.", said once.
- **Changing ownship resets tracks silently.** Switching drones clears all per-target
  memory (every range was relative to the old drone) and says "Now watching …". Also found
  live, when the manual pin handed over to DEMO-2.
- **TFR altitudes.** MSL limits are used as given. An AGL floor of 0 means the surface.
  Other AGL limits use the ground under the drone (MSL − AGL); without that, the limit
  fails wide. An unknown ceiling ("see NOTAM", and NDA TFRs) is capped at 18,000 ft MSL so
  airliners at FL350 don't set it off. A TFR whose edge is more than 10 nm from the drone
  isn't watched.
- **"Miles" means nautical miles**, the aviation convention. The rings are in nm.
- **Remote ID tracks (`src:"rid"`) from the station are dropped.** Sentry is about crewed
  aircraft, and the RID feed would include our own drone.
- **Callsigns are spelled out for TTS** ("N 3 8 8 K M"). Otherwise the engine reads "three
  hundred eighty-eight", which is harder to catch over noise. Names with spaces are left
  alone.
- **The first sighting of an aircraft already inside a zone** is announced as "Traffic
  *inside* TFR …", which is truthful: the entry itself wasn't observed.

## Drone selection and controller protection (v0.2, 2026-09-23)

Owner's decision: "yes on your callsign thing, but for fallback just offer to run the
protection around the controller, have the user fill out info for the cylinder(s), yes on
[serial allowlist] also; while typing the [callsign], offer auto-complete with known names."

- **Pattern language.** `#` one digit, `*` any run, case-insensitive; spaces and hyphens are
  removed from both sides before matching, because DroneSense callsigns are typed by hand
  ("DEMO-1 Pilot", "DEMO-1 Pilot"). The whole callsign must match, so `DEMO-1` does not
  catch "DEMO-1 Pilot" (use `DEMO-1*`). `re:` gives a plain regex matched with *find*
  semantics against the raw callsign, which is what people expect from a regex box.
- **Tiers, then freshness, then stickiness.** Airborne pattern match > airborne serial match >
  grounded pattern > grounded serial > controller. A grounded match is still better than the
  controller: it is our drone, powered on at the pad. Within a tier the freshest `lastUpdate`
  wins, but a drone already watched is kept while it stays in the best tier, so two drones
  reporting a second apart don't flip "Now watching" every poll.
- **A drone that vanishes from the feed is held** until its last position is 15 s old (engine:
  "Drone position lost"), then 30 s (fallback, spoken). Found on the emulator: the first
  version dropped it at once because a missing drone was not a candidate; a test now covers it.
- **Speech ownership.** With a selector, `AlertEngine(externalSelection = true)` stays silent
  on selection changes (the selector speaks them) and only says lost/regained for the same
  drone. Changing what is protected still clears track memory silently.
- **Start-up grace.** "No drone selected" is held for 5 s after arming so it isn't spoken 2 s
  before the first fleet poll lands; the mode shown on screen is still the true one.
- **Cylinders replace the rings** in controller mode. Inside any cylinder = caution; the
  predictive rule is the drone's CPA rule about the controller, gated on the aircraft's
  altitude now or at CPA being inside a cylinder. Exact circle tests (not the 48-gon).
  There are no rings under the warning here, so the predictive rule has its own hysteresis
  (+0.2 nm on the CPA limit and +15 s on the look-ahead while already warning); without it
  the demo replay went warning / "clear" / warning at 11:53:03–11:53:14.
- **Honest geometry in the replay.** N388KM passed ~2,700 ft above DEMO-1's launch point.
  A 1 nm SFC–1,500 ft cylinder is therefore (correctly) silent; the tests use SFC–3,000 ft
  for the entry case and assert the 1,500 ft case stays silent. The entry callout lands on
  the 1 s tick after the physical 1 nm crossing (11:53:39.7 → 11:53:41).
- **Known callsigns.** Live fleet polls feed a persisted history (callsign, serial, last
  seen); Settings also does one fleet fetch when opened. Replay drones are kept for the
  session only. `/api/live/drone-ids` returns only `ds:<uuid>` ids, so it isn't used.
- **Stationary controller.** A fix up to 60 s old is used (GPS and network providers are
  both requested, freshest wins); the UI shows its real age and accuracy.

## Pinned serial, self-update, release signing (v0.3, 2026-09-24)

Owner: "Let's do serial number also, type it once and it knows what drone that controller
needs to watch forever."

- **The pin is its own top tier**: pinned airborne > pinned on the pad > airborne pattern >
  airborne serial > grounded pattern > grounded serial > controller. A pinned airframe that is
  still on the pad beats an airborne pattern match: the owner said it is *the* drone for
  this controller, and a pad-sitting M4T that is about to launch is the one to watch. When the
  pattern matches a different drone, "Pinned aircraft wins" is spoken once per episode (it
  resets when the pin stops being watched), and the reason stays in the drone panel's note.
- **"Watching" or "Now watching"**: "Watching …, this controller's aircraft" when the pilot has
  heard nothing yet. That covers start-up, including the pin arriving while the 5 s "No drone
  selected" grace is still holding its line, which is then dropped. "Now watching …" when it
  replaces something that was already spoken (another drone, or the controller). Switching from
  callsign mode to pinned mode on the **same** airframe (the pilot pins the drone being watched)
  says nothing: nothing changed about what is protected.
- **"Forever" is re-evaluation, not a timer.** The selector re-ranks the live list every second,
  so a pinned airframe that shows up an hour later is taken at once (a test covers 3,600 s).
  The note "Pinned … not in the feed; still looking" is visible the whole time.
- **Serials are compared trimmed and case-insensitively.** The feed's `serial` is a free
  string, and the pilot may type it in lower case.
- **Persistence: SharedPreferences, not DataStore**, like every other setting (see Architecture).
  The pin is kept until the pilot clears it. The replay drone got a clearly **synthetic** serial
  (`DemoReplayFixture.DRONE_SERIAL`, not DEMO-1's real one) so the pin can be exercised in replay.
  `DemoSelectionReplayTest` asserts that pinning changes *who* is protected and nothing
  else: the callouts are identical to the pattern run.
- **Self-update from public GitHub releases**: `releases/latest` unauthenticated. The pure logic is in
  `:core` (`SemVer`, `Releases.parseLatest`, `UpdatePolicy`) and is tested: numeric rather than
  lexical comparison, pre-release below release, and a garbled tag is never "newer". Automatic
  checks: once per 24 h after a success, once per hour after a failure, triggered by arming,
  opening the app/Settings, and the service watchdog (cheap: the policy is one prefs read).
  Offline or HTTP 403/429 only updates the status line; the pilot is never nagged about a
  failed check.
- **Install only on a tap, via a PackageInstaller session.** flightdeck-air learned in the field
  that the `ACTION_VIEW` intent sometimes showed no prompt at all, and that a truncated download fails
  in the installer with no UI. So Sentry verifies the length, the package name and that the version is
  newer *before* committing a session, which always returns a result: the confirm screen, or an error
  Sentry can show. No FileProvider is needed, because the session reads the file itself. A signature
  conflict is turned into the uninstall-once instruction.
- **"Install unknown apps"** is checked first (`canRequestPackageInstalls`). If it is off, a dialog explains
  it and opens Android's page for Sentry. **Updating while armed** needs a second confirmation, because
  replacing the app stops callouts until it is reopened (or until the opt-in re-arm-on-update brings it back).
- **Signing.** A single release key (`~/.sentry-release.jks`, RSA 2048, 10,000 days, cert
  SHA-256 `07d612bf…51dc`) is used by CI through repository secrets. The release workflow fails if
  `apksigner` shows any other certificate. Settings shows whether the installed copy has that
  certificate, so a pilot on an old debug-signed build knows ahead of time that a one-time
  uninstall is coming. v1 (JAR) signing is not produced: minSdk 26 only needs v2.
- **Version from one file.** `VERSION` → `versionName`; `versionCode = M·10000 + m·100 + p`, so it
  can only go up with the version (0.2.0 was 2, 0.3.0 is 300). The release workflow refuses a tag
  that doesn't match `VERSION`. `-PsentryVersion=` builds a test copy (0.2.9 was used to test the
  updater against the published v0.3.0).
- **Minify stays off.** There is no proguard config yet that has passed a smoke test.

## Voice path

- AudioAttributes `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH`, with
  focus `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. DroneSense's audio ducks rather than pausing.
  A tone (ToneGenerator on STREAM_MUSIC) plays before each utterance:
  warning `TONE_CDMA_HIGH_SS` 700 ms, caution `TONE_PROP_BEEP2`, advisory `TONE_PROP_BEEP`,
  info `TONE_PROP_ACK`.
- The queue is ordered by priority and keeps **one pending item per aircraft**: a newer
  callout replaces an older one that hasn't been spoken yet. Items older than 15 s are
  dropped, because stale speech is wrong speech. Observed at 4× replay: the 11:53:17
  warning was replaced by the 11:53:23 TFR-entry callout.
- **Known trade-off:** a long predictive warning takes about 6 s to say. At 1× replay, the
  TFR-entry callout (dispatched at 11:53:23) was *spoken* starting about 6 s later, after
  the 11:53:17 warning finished. Sentry doesn't interrupt an utterance that is already
  playing. Shortening the predictive sentence is a candidate for tuning with the owner.
- Every callout is logged as `CALLOUT[...]` and every utterance as `SPEAK [...]` under the
  `Sentry` logcat tag. The UI's log panel shows the same lines.

## Notifications

- The `status` channel (LOW) is the ongoing foreground notification. It reads, for
  example, "Watching DEMO-1 · 12 targets · station+cloud" and only updates when the text
  changes.
- The `alerts` channel is HIGH importance and silent, since Sentry plays its own audio; it
  vibrates. It uses CATEGORY_ALARM and a 30 s timeout. There is one banner per aircraft
  (updates replace it) and one per kind of health event. Heads-up banners over another app
  were verified on the emulator.

## Debug-only adb hooks

`MainActivity` accepts `--es sentry_action replay|test|set` (`set` takes `pattern`, `serials`,
`pinned`, `protect_controller`, `station`, `station_url`, `worker`, `elev`) **only when
`BuildConfig.DEBUG`**, for scripted demos. It will never disarm Sentry. Release builds
ignore it.

## Not done / out of scope

- Controller mode on the real RC Plus: the emulator's `geo fix` did not reach the location
  service, so the emulator runs used a shell test provider (lat/lon only) plus the elevation
  override. Real GPS altitude handling and permission prompts need a check on the device.
- The fleet feed was empty on 2026-09-23 evening, so callsign autocomplete and the live
  stale/fallback path were exercised against a mock DroneSense feed (test callsigns).


- QR scanning for the fleet token: the token is pasted instead (clipboard button).
- User-drawn geofences: out of scope. Circles and GeoJSON import are supported.
- Beacon discovery could not be exercised on the emulator, because the emulator's NAT
  doesn't pass LAN broadcasts. The code path is simple and the typed-URL path was verified
  against a mock station.
- The emulator isn't a real Doze or OEM-battery environment. Screen-off operation was
  verified on the emulator; it still needs a check on the actual RC Plus.
- Audio could not be heard headless (`-no-audio`). TTS initialised with Google TTS, and
  utterance start/done callbacks fired with realistic durations, which indicates synthesis
  ran.
