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
  every 2 s; the `DroneSelector` watches this controller's pinned aircraft (by serial) and
  nothing else (v0.3.3, see below). When it is not in the feed, or none is pinned, Sentry protects
  cylinders around the controller's own GPS, and every switch is spoken.
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
- **Hysteresis.** A ring has to be exceeded by 0.2 nm, and the ceiling above the drone by 200 ft,
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

> **Superseded in v0.3.3:** the callsign pattern, serial allowlist, picker and "protect this controller" switch
> were removed. Selection is now the pinned serial, else the controller (see "Bound to one aircraft" below).
> The controller cylinders, the drop-out hold and the controller-GPS rules below still apply.

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

> **Selection part superseded in v0.3.3:** the pin is no longer a top tier above other rules; it is the only drone
> that can be watched. The self-update and signing parts below still apply, plus "never while armed" (v0.3.3).

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
- **Verified end to end on the emulator (2026-09-24).** 0.2.0 (CI debug key) refuses 0.3.0 with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (`docs/install-0.3.0-over-0.2.0.txt`). A local 0.2.9 found the
  published v0.3.0, raised the notification and the banner, sent the pilot through "Install unknown apps", checked
  the 5,419,381-byte APK and opened the installer. After tapping Update it was running 0.3.0 (versionCode 300),
  with settings kept. Found there and fixed on `main` after the tag: the status line showed a message saved before
  the update ("Sentry 0.3.0 is available" on 0.3.0). It is now derived from the installed and latest versions, with a
  failed last check appended.

## Fitting the RC Plus screen (v0.3.2, 2026-09-24)

The owner's photo of the real DJI RC Plus showed the main screen's buttons as "AR", "T", "Dr" and "Sett", with the
left panel cramped. **Root cause:** every layout had been sized on an emulator at 1920×1200 / **240 dpi**
(1280×800 dp). The RC Plus has a 7" 1920×1200 panel at about **320 dpi** (density 2.0), which leaves about
**960×600 dp**, a quarter less width. Reproduced on an AVD with the real density (`rc-plus`, screenshot 27:
"Tes", "Dron", "Settin", and the Sources and callouts panels clipped mid-line).

- **Decide by width in dp, not by device.** `ScreenLayout.compactFor(widthDp)` is true below 1000 dp (unknown width
  counts as compact, because that plan fits everywhere). It takes `Configuration.screenWidthDp`, so a controller with
  a larger display size or font setting gets the compact plan as well. `ScreenLayout` is plain Kotlin and has unit tests.
- **Reflow, don't drop.** In compact mode, the compass column shrinks (weights 1.2 / 0.85 / 1.35, against 1.15 / 0.95 / 1.2
  in wide mode) and the drone panel moves under the compass. That leaves the left column for the banner, the Sources table and the
  buttons, and gives the callouts column the extra width. The compass is square and takes the height left over under the
  drone panel, so a long controller-mode description shrinks the compass rather than being cut. Settings becomes
  one scrolling column (`ScreenLayout.settingsColumns`).
- **Labels never truncate.** The buttons have one line, uniform auto-size from 11 to 20 sp, and a fixed height (56 dp in compact
  mode, 76 dp in wide mode). In compact mode ARM gets its own full-width row, with Test / Drone… / Settings below it. `baselineAligned="false"`
  on the row: with auto-size the labels can end up different sizes, and at font scale 1.3 baseline alignment pushed
  two buttons down.
- **Tables move text instead of cutting it.** The monospace Sources and Targets rows put their trailing detail on
  an indented second line when it doesn't fit the measured width. The callouts and targets panels show as many
  whole lines as fit and end in "…" (newest first, so only the oldest callout is shortened; all callouts are also
  in the log and notifications), instead of the panel edge cutting a line in half.
- **Radar drawing constants scale with density** (they were raw px tuned at density 1.5) and shrink to 70% at most
  on a small compass. The "S" label now stays inside the view; before, it was drawn past the bottom edge.
- **Verified:** `rc-plus` (API 34, 320 dpi) disarmed, replay, live-armed, Settings, picker; the old 240 dpi profile (wide layout
  unchanged); the **Android 10 / API 29** image at 320 dpi (what the RC Plus runs): the replay produced all callouts, with no
  crash and nothing under the status bar or the navigation bar. Font scale 1.3 was also checked. **Not verified:** the real controller.
  Its exact display-size setting is unknown (its truncation was worse than the emulator's), which is why the decision is
  by measured dp.

> **Corrected in v0.3.3:** 320 dpi was still too generous (see below). The two-row button box and the "fit whole
> lines" callout clipping were replaced by a pinned action bar and scrolling columns.

## v0.3.3 (2026-09-24): the real controller, one bound aircraft, the flight volume, coexistence

### The RC Plus screen, part 2: it behaves as 400 dpi, and nothing may depend on scrolling

The owner, on the real controller: "Can't scroll on Sentry, so can't disarm, select settings, etc." An AVD at
**density 400** (1920×1200, Android 10, default font scale; AVD `rc-plus-29`) reproduces the earlier photo **exactly**
("AR", "T", "Dr", "Sett", screenshot 37), where 320 dpi gave "Tes", "Dron", "Settin". So the app gets about
**768×480 dp**. Armed in controller mode, 0.3.1's drone panel pushed the whole button row off the bottom (38), and the
root `LinearLayout` could not scroll (39: a swipe changes nothing). That is the stuck state the owner hit.

- **Pinned action bar.** ARM/DISARM · Test · Settings sit in a bottom bar that is a direct child of the root,
  **outside every scroll container**. The bar is always on screen, whatever the panels contain.
- **Every column scrolls.** Each of the three columns is its own `ScrollView` (`fillViewport`, visible scrollbar).
  The columns are siblings, never nested, so there is no nested-scroll conflict. Verified by `adb shell input swipe` on
  each column, with before and after screenshots (40, 41).
- **`wrap_content` + weight inside a scroll view, never `0dp` + weight.** In a scroll view's unbounded measure pass,
  `LinearLayout` re-shares the wrapped heights of `0dp` children by weight. That gave Targets empty space and clipped
  Callouts below their text, where it could not be scrolled to. Found with `dumpsys activity top` bounds. With
  `wrap_content` the weight only hands out spare height when the column fits. The compass asks only for its
  `minHeight` (170 dp) in the unbounded pass, so it shrinks before a column starts to scroll.
- The "fit whole lines and end in …" callout clipping from 0.3.2 was removed: callouts now scroll instead.
- Settings: Back/Save stay pinned at the top and the page scrolls. The order is rings (and targets shown),
  cylinders, this controller's aircraft, then everything else, with the replay last.

### Bound to one aircraft (selection is the pinned serial, else the controller)

Owner: "I don't want it to pick another variable, needs to be a constant. We can't have the pilot thinking his drone is
protected but really it's protecting another. Needs to be a fixed setting, per controller." And: "just serial number
or controller as a fallback."

- `DroneSelector` has exactly two outcomes: **PINNED** (the airframe whose serial matches, airborne or on the pad) or
  **CONTROLLER**. Other drones are never candidates. The callsign pattern, serial allowlist, multi-match logic, the
  Drone… picker and the manual "protect this controller" were **deleted**, not switched off, together with their tests.
  The pre-0.3.3 prefs are simply no longer read.
- Pinned but absent → `waitingForBound`: banner "WAITING FOR THIS CONTROLLER'S AIRCRAFT · <serial>", said once on arm
  (after the 5 s start-up grace) and once per drop-out (after the existing 15 s lost and 30 s fallback). The controller
  cylinders protect the pilot meanwhile. It is re-acquired only on a fresh report (15 s old or newer).
- "Bound to: <serial> · <callsign>" (or "NO AIRCRAFT PINNED …") is on the main screen in every state, armed or not.
- Pinning without typing: Settings lists the **aircraft in the feed now** (serial · callsign · model, from one fleet
  fetch when Settings opens, or "Refresh list"). Tapping one pins it. `model` was added to `Ownship` (FDA
  `drone.model`, DroneSense `model`).
- Tests (`PinnedSelectionTest`, `DemoSelectionReplayTest`): present airborne and on the pad; absent → controller
  for 600 s with another airborne drone in the feed, which is never watched; appears an hour later → bound at once;
  drop-out → waiting → back; and a demo run bound to an absent airframe gives exactly the nothing-pinned cylinder
  callouts and never watches DEMO-1. A mutation that lets a pattern match while bound fails 5 tests.

### The flight volume: surface up to X ft above the drone

Owner: "we never want anything flying under us." `SentryConfig.ceilingAboveFt` (default 2,000; Settings "Ceiling above
aircraft") replaces the symmetric ±`verticalBandFt`. A target is inside when `dv <= ceiling (+200 ft hysteresis once
alerting)`, with no lower limit; unknown altitude is inside. The predictive rule uses the same test on the smaller
of "now" and "at CPA". Tests: 3,000 ft below at 0.4 nm → WARNING, 3,000 ft above → silent, the same pair for the
predictive rule, and the ceiling as a setting. Restoring `abs(dv)` fails 2 of them. The demo callouts are
unchanged (N388KM was within a few hundred feet of DEMO-1). Cylinder floors default to SFC, shown as "SFC".

### What the Targets list and compass show

`TargetDisplay.filter` (pure, tested): within 10 nm of the watched aircraft, or 15 nm of the controller when protecting
it, and hidden above 18,000 ft judged on `alt_geom`, else `alt_baro`. Unknown altitude stays shown. All three are
settings. **An aircraft at advisory or worse is always shown**, whatever the filter, so the screen never hides what
Sentry is talking about. The engine still evaluates everything within the traffic radius, so alerts are unaffected.
The label says so: "Targets · 1 within 10 nm of aircraft · ≤18,000 ft · 16 hidden".

### Why the Drone feed is empty

The owner lost an hour to a missing token. `FleetStatus.of` (pure, tested) turns the two fleet fetches into one reason:
"NO TOKEN: paste the fleet token in Settings", "TOKEN REJECTED (HTTP 401|403): check the fleet token",
"feed error: …", "no drones in feed", or "N drones in feed (dronesense: HTTP 500)". The Sources row shows it, up to
60 characters, wrapped instead of cut, and so does Settings under the aircraft list.

### Coexistence with DroneSense

Owner: "make sure this software never impacts DroneSense on the controller while we're flying."

- No activity is ever started by the service, the boot receiver or a notification action. Alerts are heads-up
  notifications (no full-screen intent) and speech. The main screen comes forward only from the pilot's own tap on
  Sentry's notification (`PendingIntent` to `MainActivity`, `SINGLE_TOP`) or icon. Checked: armed + replay with the
  Android Settings app in front, `mResumedActivity` sampled every 5 s for 3 min was Settings in 36/36 samples
  (`docs/coexistence-resumed-activity-3min.txt`).
- The updater never opens the installer while armed. `startUpdate` refuses with a toast, `downloadAndInstall` refuses,
  and the install receiver re-checks just before `startActivity`: if the pilot armed during the download, the session
  is abandoned. The update-available notification is on a LOW (silent) channel.
- Audio focus `GAIN_TRANSIENT_MAY_DUCK`, requested per callout and released right after it. It used to be held
  across a whole queue. No media session.
- No prompts while armed: permissions are requested on the ARM tap and arming happens in
  `onRequestPermissionsResult`. The battery-optimisation request moved to Settings only.
- Crash isolation: per-poll `catch (Exception)` plus `catch (StackOverflowError)`, and `Parsers.parse` refuses JSON
  nested deeper than 64. `MalformedPayloadTest` found that `[[[[…` made the JSON library throw `StackOverflowError`, an
  Error the poll catch did not stop, so a hostile or corrupt payload could have killed the process. Every parser is
  now tested with non-JSON (only an ordinary Exception may escape) and wrong-shape JSON (parses to nothing).
- Resource use (Android 10 AVD at 400 dpi, 4 vCPU, software GL; armed live 5 min, another app in front):
  **3.3 % of one core** (0.8 % of the device); **PSS 103 MB on average** for a service-only process (76 MB at the end
  of the window), 138 MB for a process that had drawn the UI (hwui native heap under software GL; not measured on
  real hardware). RSS averaged 184 MB, but RSS counts shared framework pages (`com.android.phone`: 138 MB RSS for
  32 MB PSS on the same image), so it can't meet a 120 MB target for any app here. Full log:
  `docs/perf-armed-5min-rc-plus-29.txt`.
- No USB, serial or DJI SDK code or permission.

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

`MainActivity` accepts `--es sentry_action replay|test|set` (`set` takes `pinned`, `station`,
`station_url`, `worker`, `elev`) **only when
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
