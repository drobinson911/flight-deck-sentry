# Flight Deck Sentry

A small, single-purpose Android companion app for the **DJI RC Plus** smart controller.
It runs in the background while **DroneSense** flies the drone in the foreground, and it
**speaks** when a crewed aircraft enters a TFR/geofence or comes close to the drone.

## Why it exists

In a real encounter recorded from public ADS-B data, Cirrus SR22T **N388KM** crossed TFR 0/0000 and passed
about 200 ft below drone **DEMO-1** (DJI M4T) at **0.24 nm**. Nothing warned the pilot: the
truck's ADS-B station audio was drowned out by the highway noise, ForeFlight doesn't alert, and
DroneSense shows no proximity warning. The public ADS-B feed also showed N388KM's altitude as
`"ground"` for the whole pass, at 160 kt. Sentry exists so this never happens silently again.

Replaying the real data from that day with the v0.3.5 cadence, Sentry says:

| Replay time (PDT) | Severity | Spoken |
|---|---|---|
| 11:52:35 | advisory | "Traffic, N388KM, southwest, 3.0 miles, 100 above, converging." |
| 11:52:57 | **warning** | "Warning. Traffic, N388KM, southwest, 1.9 miles, 100 below, converging, closest 2,700 feet in 40 seconds." |
| 11:53:03 | **warning** | "Traffic, N388KM, southwest, 1.6 miles, 100 below, closing." |
| 11:53:14 | **warning** | "Traffic, N388KM, southwest, 1.1 miles, 200 below, closing." |
| 11:53:20 | **warning** | "Traffic, N388KM, southwest, 5,300 feet, 300 below, closing." |
| 11:53:23 | **warning** | "Traffic entering TFR 0/0000, N388KM, southwest, 4,700 feet, 300 below, converging." |
| 11:53:29 | **warning** | "Traffic, N388KM, south, 3,200 feet, 300 below, closing." |
| 11:53:35 | **warning** | "Traffic, N388KM, south, 2,100 feet, 300 below, closing." |
| *11:53:40* | advisory | "N388KM passing, diverging." *(closest approach, 0.24 nm)* |
| 11:54:25 | advisory | "Traffic, N388KM, northeast, 1.9 miles, 500 below, diverging." |
| 11:54:54 | info | "N388KM clear, diverging." |

The short "… closing." callouts are the 6 s close band: the aircraft was inside 0.5 nm, or on course to pass
inside 0.5 nm within 30 s. The gap at 11:53:03–11:53:14 is where the predicted closest approach briefly moved
beyond 30 s, so the 20 s band applied until it came back. See "Alert cadence" below; `CadenceTest.demoTimeline`
prints this table.

The first warning comes **43 seconds before the pass**. The same data run through the full selection
path (`DemoSelectionReplayTest`, with the replay drone's synthetic serial pinned; it carries the callsign
"DEMO-1 Pilot") says "Watching DEMO-1 Pilot, this controller's aircraft." at 11:51:50 and then exactly the
callouts above.

With **no aircraft pinned** (or the pinned one not in the feed), Sentry protects the controller at DEMO-1's launch point (39.4290, −120.0344, 5,100 ft).
N388KM passed about 2,700 ft above that point, so a 1 nm cylinder with a 1,500 ft ceiling stays silent
(it went over the top: a test asserts this), and a 1 nm SFC–3,000 ft cylinder gives:

| Replay time (PDT) | Severity | Spoken |
|---|---|---|
| 11:51:55 | info | "No aircraft pinned. Protecting this controller." (pinned but absent: "Waiting for this controller's aircraft.") |
| 11:53:03 | **warning** | "Warning. Traffic, N388KM, southwest, 2.6 miles, 2,700 above, converging, closest 2,500 feet in 52 seconds." |
| 11:53:23 | **warning** | "Traffic entering TFR 0/0000, N388KM, southwest, 1.8 miles, 2,700 above, converging." |
| 11:53:37 | **warning** | "Traffic, N388KM, south, 1.1 miles, 2,700 above, closing." |
| 11:53:41 | **warning** | "Traffic entering ops area, N388KM, south, 6,000 feet, 2,700 above, converging." |
| 11:53:47 | **warning** | "Traffic, N388KM, south, 4,300 feet, 2,700 above, closing." |
| 11:53:53 | **warning** | "Traffic, N388KM, south, 2,900 feet, 2,700 above, closing." |
| 11:53:59 | **warning** | "Traffic, N388KM, south, 1,600 feet, 2,600 above, closing." |
| 11:54:03 | advisory | "N388KM passing, diverging." |
| 11:54:29 | info | "N388KM clear, diverging." |

N388KM physically crossed 1 nm from the launch point at about 11:53:39.7 (1.03 nm at 11:53:39, 0.81 nm
at 11:53:44); the 1 s tick after that is 11:53:41. Its closest approach to the launch point was 0.23 nm
at 11:54:03. The same run with N388KM's altitude
as `"ground"` (the way the public feed saw it) produces the same callouts, with
"altitude unknown" in place of the vertical figure. Both runs are unit tests
(`DemoReplayTest`), and they use the same bytes the app bundles for its replay mode.

## The DJI MSDK constraint

DJI's Mobile SDK allows **one** app to own the aircraft link, and DroneSense owns it.
Sentry therefore has **no DJI dependency at all** and never talks to the aircraft. It gets
the drone's position from the network (see below). It keeps working alongside DroneSense,
ForeFlight or anything else.

## Data sources (all run at the same time, never either/or)

| What | Where | Cadence | Notes |
|---|---|---|---|
| Drone position (primary) | `GET {worker}/api/live/our-drones` + `X-Fleet-Token` | 2 s | Flight Deck Air ingest shape (`drone.id/callsign`, `pos.lat/lon/altMslFt/altAglFt`, `_ageMs`). Its `airsense[]` contacts are used as a **third traffic source**. |
| Drone position (primary, 2nd feed) | `GET {worker}/api/live/dronesense` + `X-Fleet-Token` | 2 s | DroneSense `with-sensors` elements (`callSign`, `latitude/longitude`, `altitudeMsl/Agl` in **metres**, `lastUpdate` in unix **seconds**). This is where a DroneSense-flown drone shows up. `rtsp_url` is never read. |
| This controller's aircraft not in the feed (or none pinned) | This controller's GPS (GPS + network providers) | 1 s | Sentry protects the pilot's **cylinders around the controller** (see below). Spoken: "Waiting for this controller's aircraft." / "No aircraft pinned. Protecting this controller." |
| Traffic A: truck station | `GET http://<station>:8080/data/aircraft.json` (Overwatch, readsb shape) | 1 s | Typed URL **and** Overwatch UDP beacon auto-discovery (port 41120). Off by default. "Station link lost / regained" is spoken. |
| Traffic B: cloud | `GET {worker}/api/live/adsb` (browser User-Agent) | 5 s | About 1,000 aircraft nationwide, filtered to 30 nm around the drone. |
| TFRs | `GET {worker}/api/tfrs` | 10 min | GeoJSON; `NOTAM_NUMBER`, `_ALT_L/H_VAL/UOM/CODE` (ALT=MSL, HEI=AGL, FL). Last good copy cached on disk. |
| Geofences | Settings | — | Circle (centred on the drone or on a fixed point) + polygons imported from a GeoJSON file. |

`{worker}` defaults to `https://uas-app.drobinson911.workers.dev`. Traffic is merged per ICAO
hex, and the fresher **position** wins. Ages come from each feed's *relative* fields
(`seen_pos`, `_ageMs`), so a laptop with a wrong clock can't make stale data look fresh.

## Which aircraft Sentry protects: this controller's aircraft, else the controller (v0.3.3)

Owner, 2026-09-24: "just serial number or controller as a fallback", "needs to be a constant, per controller.
We can't have the pilot thinking his drone is protected but really it's protecting another."

- **Settings → This controller's aircraft:** type the airframe serial once (it autocompletes from every serial
  Sentry has seen), or tap one of the **aircraft in the feed now** (serial · callsign · model) to pin it. It is kept
  until you clear it.
- **Pinned and in the feed** (airborne, else on the pad): Sentry protects **that aircraft only**. "Watching DEMO-1
  Pilot, this controller's aircraft."
- **Pinned but not in the feed:** the banner reads "WAITING FOR THIS CONTROLLER'S AIRCRAFT · <serial>". Sentry says
  "Waiting for this controller's aircraft." once when armed and once each time the aircraft drops out, and the
  **controller cylinders** protect the pilot meanwhile. It switches to the aircraft the moment it appears.
  **No other drone is ever watched**, however close or airborne: there is no callsign pattern, serial list or picker.
- **Nothing pinned:** the controller cylinders only. "No aircraft pinned. Protecting this controller."
- The main screen always shows what this controller is bound to: "Bound to: <serial> · <callsign>", or "NO
  AIRCRAFT PINNED — protecting this controller only (Settings)". The Sources table has a Selection row (BOUND /
  WAITING / CONTROL).
- Drop-out timing: after 15 s without a position the engine says "Drone position lost". After 30 s Sentry switches to
  the cylinders and says "Waiting for this controller's aircraft." The aircraft is acquired again only on a fresh
  report (15 s old or newer).
- The **Drone feed** row always says why there are no drones: "NO TOKEN: paste the fleet token in Settings",
  "TOKEN REJECTED (HTTP 401): check the fleet token", "feed error: …", or "no drones in feed".

## Controller protection cylinders

Settings → **Controller cylinders**: any number of cylinders, each with a name, radius (nm or ft),
floor (default **SFC**, the surface) and ceiling in **ft above the controller** (default) or **ft MSL**, and an
on/off switch. Defaults: "ops area" 1 nm SFC–1,500 ft and "advisory area" 3 nm SFC–3,000 ft. They are used while
the pinned aircraft is not in the feed, or when none is pinned:

- The drone rings are replaced by the cylinders (the radar draws them around a square "controller" mark).
- **Entry:** "Traffic entering ops area, N388KM, south, 6,000 feet, 2,700 above, converging." (caution,
  or warning if the predictive rule is also firing). Unknown aircraft altitude counts as inside.
- **Predictive:** the same CPA rule as for a drone, about the controller: closest approach within 60 s
  inside the warning ring (0.5 nm), with the aircraft's altitude now or at CPA inside a cylinder.
- The controller's elevation comes from the Settings override if set, else Android's MSL altitude (API
  34+), else the raw GPS altitude, which is WGS-84 ellipsoid height (~100 ft low in California) and is
  labelled "≈ellipsoid" on screen. With no elevation at all, AGL limits fail wide.
- A controller fix is used while it is ≤ 60 s old (a stationary controller); its true age is shown. No
  usable fix for 15 s → "Controller GPS unavailable. Nothing protected." and a red banner.

## Alert rules (pure Kotlin, `core/`, unit-tested)

- **Rings around the drone:** 3 nm advisory, 1 nm caution, 0.5 nm warning (all configurable). **Vertically, from the
  surface up to 2,000 ft above the drone** (Settings → "Ceiling above aircraft", v0.3.3; it was a symmetric ±2,000 ft
  band before). Owner: "we never want anything flying under us". Anything below the drone is inside, however far
  below; unknown altitude is inside. So a target 3,000 ft below at 0.4 nm is a warning, and 3,000 ft above is silent.
- **Predictive:** if the closest point of approach within 60 s falls inside the 0.5 nm ring, with the aircraft inside
  that volume now or at the CPA, a **warning is issued now**.
- **TFR / geofence entry:** a transition from outside to inside, when the aircraft's altitude is inside the zone's floor/ceiling. **Unknown altitude counts as inside** and is spoken as "altitude unknown". Only TFRs within 10 nm of the drone are watched.
- **What is spoken:** cardinal direction from the drone (north … northwest); distance in nautical miles to one decimal, or in feet under 1 nm; "N hundred above/below"; converging / diverging / passing (from the range rate). Callsigns are spelled out ("N 3 8 8 K M") so they carry over rotor and road noise.
- **Tones:** a different tone per severity plays before the speech.
- **Alert cadence, per aircraft (v0.3.5, owner-approved; `core/Cadence.kt`, `CadenceTest`).** How often Sentry
  repeats a callout about the same aircraft depends on its range and whether it is closing:

  | Where the aircraft is | Repeat | What is said |
  |---|---|---|
  | beyond 3 nm, or diverging | none (entry and escalation only) | "clear" once when it leaves 3 nm or the vertical band |
  | 1–3 nm, not diverging, at caution or warning | every **20 s** | the full sentence |
  | 1–3 nm, advisory level | every **30 s** (unchanged) | the full sentence |
  | 0.5–1 nm, not diverging | every **12 s** | the full sentence |
  | inside 0.5 nm, or a predicted pass inside 0.5 nm within 30 s | every **6 s** | the **short** sentence: "Traffic, N388KM, west, 1,500 feet, 200 below, closing." |
  | opening after a close pass | "N388KM passing, diverging." **once**, then every **45 s** while still inside 3 nm | then "clear" |

  - The bands are the configured rings (defaults 3 / 1 / 0.5 nm). "Not diverging" includes a parked or crossing
    aircraft. A **close pass** means Sentry called it inside the caution ring (1 nm) or with a predictive warning.
  - **Escalation always interrupts the timer**: none → advisory → caution → warning, or a predictive warning, is spoken
    at once. Dropping to a lower level is silent, and the new band's timer applies.
  - **Zone entry** (TFR, geofence, controller cylinder): **one** "Traffic entering …" callout ("inside" when first
    seen already in; re-entering the same zone within 20 s is not announced again). After that the aircraft falls
    under the range cadence above, not a flat 20 s. For example, a cylinder entry at 0.8 nm is repeated every 12 s
    (`cylinderCadenceEntryThenRangeCadenceThenClearOnce`).
  - **Clear:** once, when it leaves every ring or cylinder, with 0.2 nm and 200 ft exit hysteresis.
  - **Gone:** "track lost" once for a target that disappears after being called, or "on the ground" when it lands.
  - **Voice queue** (`core/CalloutQueue.kt`): at most **one waiting sentence per aircraft** (a newer one replaces an
    unspoken older one). Most severe first; at the same severity the **closer aircraft first**. A sentence that has
    waited more than 15 s is dropped, and one already playing is not interrupted.
- **Targets shown** (list and compass only; alerts are unaffected): within **10 nm of this controller's aircraft**
  when it is watched, else within **15 nm of the controller**, and hidden above **18,000 ft** (GPS altitude, else
  baro; unknown altitude stays shown). All three are settings. An aircraft Sentry is alerting on is always shown.
- **Stale data is never announced:** targets whose `seen_pos` is over 30 s old are dropped. Sentry says "Traffic data stale" once when all traffic sources are more than 30 s old, "Drone position lost" when the drone's position is more than 15 s old (then "regained"), and "track lost" or "on the ground" for a target it had been calling.
- **Ground mode at speed:** `alt_baro:"ground"` with a speed of 50 kt or more is treated as **airborne, altitude unknown**. It is never dropped as ground traffic. (This is the N388KM lesson.)
- **Altitude:** `alt_geom` is used when present. Otherwise `alt_baro` + 300 ft, labelled "estimated" (≈) on screen. The drone's altitude is MSL from the feed.

## Voice: text-to-speech, and Sentry's own voice underneath it (v0.3.5)

The DJI RC Plus runs DJI's cut-down Android 10, which has **no text-to-speech engine** and no Google services. On it,
0.3.4 said "Voice unavailable" and played tones only. Sentry now carries its **own voice**, stacked under TTS:

- **TTS first, bundled voice underneath.** If the controller has a working TTS engine, Sentry uses it. If there is
  none, or it isn't ready, Sentry uses its **bundled voice**. If TTS fails on a callout (an error, no start within 3 s, or no finish
  within 3 s + 90 ms per character), that same callout is spoken by the bundled voice at once, and the bundled voice stays in charge for
  5 minutes before TTS is tried again. Tones and heads-up banners work either way.
- The main screen's **Voice** row names the voice in use: **"OK (bundled voice)"** or **"OK (Google TTS)"**. It
  never says "unavailable" while the bundled clips are installed. **Settings → Voice → Test voice** plays a full
  warning through that voice. **Test callout** is still there.
- **The bundled voice** is a bank of recorded clips (`app/src/main/assets/voice/`, 256 clips, about 2 MB): numbers
  (0–99, "N hundred", "N thousand"), letters A–Z, directions, the callout words, and common whole sentences such as
  "Drone position lost." Every callout the engine can produce is put together from these clips, back to back, with
  short pauses at commas. A callsign is spelled out as usual ("N 3 8 8 K M"). A cylinder or geofence name the bank
  can't say becomes "protected area" or "geofence". A drone name with a space in it is spelled out letter by letter.
- **Where the clips come from:** [Piper](https://github.com/OHF-Voice/piper1-gpl) TTS, rendered offline on the build
  machine with the voice **en_US-kristin-medium**. That voice was trained on LibriVox recordings, which are
  **public domain**. The generator and its word list are in `tools/voicebank/` (`gen.py`, `phrases.txt`). Hear the
  result in `docs/voice-sample-warning.ogg`: the sample warning, stitched exactly as the controller plays it.
- A unit test (`VoiceGrammarTest`) runs about 4,900 distinct sentences from the real engine, selector and health
  monitor through the clip grammar. It fails if any sentence needs a clip the bank doesn't have.

## Install on the RC Plus

1. Get the APK. The link below always points to the newest release (signed with the Sentry release key):
   **https://github.com/drobinson911/flight-deck-sentry/releases/latest/download/flight-deck-sentry.apk**
   Open it in the controller's browser, or download it on a computer.
2. Install it. On the controller, open the download and allow "install unknown apps" for the browser or file manager when Android asks.
   Or, with USB debugging on (Settings → About → tap Build number 7x → Developer options): `adb install -r flight-deck-sentry.apk`.
3. **Coming from 0.2.0 or older? Uninstall Sentry once first.** Those builds were signed with a throwaway CI debug
   key, and Android refuses to install a differently-signed APK over them (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
   see `docs/install-0.3.0-over-0.2.0.txt`). Uninstalling also clears Sentry's settings, so write down your pinned serial and
   cylinders first. From 0.3.0 onward every release uses the same key and installs in place.
4. Open **Flight Deck Sentry** → **Settings**. Paste the fleet token (release APKs have none built in). Set
   **This controller's aircraft** (type the serial, or tap it in the list of aircraft in the feed now), and check the
   **controller cylinders**. Optionally turn on the truck station.
5. Tap **ARM**. Allow notifications and location (the controller's GPS is the fallback protected position). Accept the **battery-optimisation exemption** so Android doesn't throttle Sentry with the screen off.
6. Tap **Test callout** to set the volume. Then switch to DroneSense: Sentry keeps running and its banners appear over DroneSense.

**Screen:** the RC Plus panel is 1920×1200 px and runs at **density 2.5 (400 dpi)**, which is about **768×480 dp**.
An emulator at 400 dpi reproduces the owner's photo of 0.3.1 exactly: "AR", "T", "Dr", "Sett". Below 1000 dp wide,
Sentry uses a compact layout: a smaller compass with the drone panel under it, and smaller type. Since 0.3.3 the
**action bar (ARM/DISARM · Test · Settings) is pinned at the bottom**, outside any scroll container, and each of the
three columns above it scrolls on its own when its content is taller than the screen. Settings is one scrolling
column with Back/Save pinned at the top. Its order is: alert rings (and targets shown), controller cylinders, this
controller's aircraft, then feeds, voice, geofences, background, app update, and the replay last.

**Settings save themselves (0.3.4).** Every field is stored about 0.4 s after you stop typing, so leaving Settings
without tapping Save loses nothing, and the armed service uses the new value within a second. A number that isn't
valid or is out of range turns the field red with its range under it (for example "0.1–50 nm"), and Sentry keeps
using the last valid value. Enter/Done, Save, Back, or a tap outside a field closes the keyboard. Save still works:
it applies everything at once and says "Saved".

## Coexistence with DroneSense (v0.3.3)

Owner: "make sure this software never impacts DroneSense on the controller while we're flying."

- **Never takes the foreground.** The service launches no activity, and alerts are heads-up notifications and
  speech only (no full-screen intents, no dialogs). The main screen comes forward only when the pilot taps
  Sentry's notification or icon. Verified: armed, replaying at 1×, with another app in front for 3 minutes,
  `mResumedActivity` was that app in all 36 samples (`docs/coexistence-resumed-activity-3min.txt`).
- **Updates never install while armed.** "Download and install" says "Disarm Sentry first" and does nothing. If
  the pilot arms while a download is running, the installer is not opened, the session is abandoned, and the update
  waits. The update-available notification is on a low-importance (silent) channel.
- **Audio:** focus is `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (never `GAIN`), requested for one callout (tone + words)
  and released right after it, so DroneSense only ducks while Sentry is talking. There is no media session.
- **No prompts while armed.** Permission prompts appear on the ARM tap, before arming, and arming happens after
  the dialog closes. The battery-optimisation prompt is only in Settings.
- **CPU, battery, network:** the pollers run the station every 1 s, the fleet every 2 s, cloud ADS-B every 5 s and
  TFRs every 10 min, each with capped backoff. The tick is 1 s and the watchdog 5 s; nothing wakes more often than once
  a second. There is one OkHttp client with 4/6/8 s timeouts and connection reuse. UI work runs only while the main
  screen is visible. Measured on the Android 10 AVD at 400 dpi, armed live for 5 minutes with another app in front:
  **3.3–3.4 % of one core (0.8 % of the device)**, and **PSS 103 MB on average** for the service alone (138 MB in
  a process that had drawn the UI). RSS was higher (184 MB on average) because it counts shared framework pages: on
  the same image `com.android.phone` shows 138 MB RSS for 32 MB PSS. Details are in `docs/perf-armed-5min-rc-plus-29.txt`.
- **No USB, serial or DJI SDK access**, and no USB permissions in the manifest.
- **Crash isolation:** every poll and every tick catches its own exceptions and shows them as a health line.
  Feed JSON nested more than 64 deep is refused before it reaches the parser, which had thrown `StackOverflowError`
  on such input (found by `MalformedPayloadTest`). Each parser is tested against malformed and wrong-shape payloads.


## Updating

Sentry updates itself from the public GitHub releases, but **it never installs without your tap**.

- **Settings → App update** shows the installed version and build, the latest release on GitHub with its
  release notes, and whether this copy is signed with the release key. **Check for update** asks GitHub now.
  **Download and install** downloads `flight-deck-sentry.apk` with a progress bar, checks it (complete file,
  package, newer version), then opens Android's installer. Tap **Install**, then **Open** to re-arm.
- Sentry also checks by itself when it starts or is armed, and again from the service watchdog. It checks at most
  **once every 24 h**, or once an hour after a failure. Being offline or hitting GitHub's 60-requests-per-hour
  limit only changes the status line. A newer release posts a silent, low-priority notification
  ("Sentry 0.3.1 available") once per version, and adds a banner to the main screen. Tapping the banner starts the
  same download and install.
- The first time, Android asks you to allow **Install unknown apps** for Flight Deck Sentry. Sentry explains
  this and opens that settings page. Come back and tap the update again.
- If Sentry is **armed** when you tap update, it asks you to confirm first: installing replaces the running app,
  so callouts stop until you tap **Open**, or until Sentry re-arms by itself if "Re-arm automatically" is on.
  Don't update in flight.
- If the installed copy is signed with a different key (0.2.0 or older, or a CI debug build), the installer
  refuses. Sentry says so and tells you to uninstall once.

## Releasing (maintainers)

The version lives in one place, the `VERSION` file (`0.3.0`). `versionCode` = major·10000 + minor·100 + patch
(0.3.0 → 300). To release, bump `VERSION` and commit, then `git tag v0.3.0 && git push origin main v0.3.0`.
`.github/workflows/release.yml` checks that the tag matches `VERSION`, runs the unit tests, builds
`assembleRelease` signed with the repository secrets `SENTRY_KEYSTORE_B64` / `SENTRY_KEYSTORE_PASSWORD` /
`SENTRY_KEY_ALIAS`, fails unless `apksigner` shows the release certificate
(SHA-256 `07d612bf02fcdfcc3a617d091909bb83d3e44cfae7e58f34bd146b6a75cc51dc`), then attaches
`flight-deck-sentry.apk` and its `.sha256` to the release for that tag. The release is created with generated notes
if it doesn't exist yet. If a run fails, fix the problem on `main`, then move the tag and push it again
(`git tag -f v0.3.0 && git push -f origin v0.3.0`), or re-run for the existing tag with **Actions → Release →
Run workflow** (`tag: v0.3.0`). The upload uses `--clobber`, so a re-run replaces the APK. Pilot-facing release
notes, which the in-app updater shows, come from `docs/release-notes/<tag>.md` when that file exists; otherwise
GitHub generates notes, which are only a changelog link. The `SENTRY_KEY_ALIAS` secret is `sentry`, so Actions
logs mask every "sentry" as `***` (for example `flight-deck-***.apk`). That only affects the logs.

The keystore is **not** in the repo. The owner's backup copy is `~/.sentry-release.jks` plus `~/.sentry-release.env`
(the password and alias) on the owner's build machine, both chmod 600. Keep them in the password manager too: losing the key means every
controller has to uninstall once more. Gradle looks for the key in the environment (`SENTRY_KEYSTORE_FILE`,
`SENTRY_KEYSTORE_PASSWORD`, `SENTRY_KEY_ALIAS`), then in `secrets.properties`, then in those two home-directory files.
Without a key, `assembleRelease` stops with a clear error. When the key is present locally, **debug builds are
signed with it too**, so a local build and a GitHub release can install over each other.

## Settings

In this order: advisory/caution/warning rings · ceiling above aircraft · baro correction · predictive look-ahead · TFR watch distance · targets shown (10 nm around the aircraft / 15 nm around the controller / 18,000 ft ceiling) · controller cylinders (add / edit / delete) + controller elevation override · **this controller's aircraft** (serial, autocomplete, tap-to-pin list of aircraft in the feed now) · fleet token (paste from the clipboard) + worker URL · truck station on/off + address + beacon auto-discover · cloud on/off · traffic radius · voice on/off + volume · circle geofence · GeoJSON geofence import · re-arm after reboot · battery exemption · app update (check, notes, download and install; never while armed) · **Replay: demo encounter** (1× / 4×, optional public-feed view).

## Replay mode

Settings → **Replay: demo encounter** plays the real DEMO-1 and N388KM tracks and the
TFR 0/0000 polygon, bundled in `app/src/main/assets/replay/`, through the **same selector,
engine, voice, and notification path** used live. The replay drone is "DEMO-1 Pilot", and the
simulated controller sits at DEMO-1's launch point. Pin the replay drone's synthetic serial to watch DEMO-1;
otherwise the replay protects the controller cylinders. A replay clock is shown in PDT. From adb
(debug builds only):

```
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 1
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 4 --ez cloud_view true
adb shell "am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action set --es pinned 1581F7K3C251F00C9B34"
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action set --ez force_bundled true   # skip TTS, as on the RC Plus
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action voice_test                    # a full warning through the voice in use
```

The replay drone carries a **synthetic** serial, `1581F7K3C251F00C9B34`. The recorded track has no serial, so
this placeholder is not DEMO-1's real one. It exists so you can pin the replay drone; see
`docs/replay-logcat-pinned-serial.txt`.

## Build & test

```
./gradlew testDebugUnitTest   # runs :core:test (engine, geodesy, CPA, parsers + malformed payloads, demo replay, selection, target display, fleet status, updater) + app tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # needs the release key (see "Releasing"); fails clearly without it
./gradlew assembleDebug -PsentryVersion=0.2.9   # a local build with another version (used to test the updater)
```

JDK 17, compileSdk 34, minSdk 26, targetSdk 33, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.14.3.
Copy `secrets.properties.example` to `secrets.properties` (gitignored) to bake in a default fleet token.

## Screenshots

**RC Plus screen profile (use it for every UI change):** AVD `rc-plus-29`: 1920×1200, `hw.lcd.density=400`,
landscape, **Android 10 (API 29)**, default font scale (recipe in `.claude/memory/reference_sentry_emulator.md`).
Screenshots 37–56 use it. 37 is 0.3.1 at 400 dpi, matching the owner's photo ("AR", "T", "Dr", "Sett"). 38 is 0.3.1
armed with the buttons pushed off-screen, and 39 is the same screen after a swipe, which does not scroll (the stuck
state the owner hit). 40–47 are the first pinned-bar build, which still had the Drone… button (removed later in 0.3.3). 40–41 are before
and after a swipe on every column, 42 is armed with DISARM reachable, 43–45 are Settings top, after a swipe and at the
bottom, 46 is that build's picker, and 47 is the replay with callouts. v0.3.3: 48 no aircraft pinned, 49–50 the rings and targets-shown settings at the top, 51 the
tap-to-pin list (mock feed, synthetic serials), 52 waiting for this controller's aircraft while another drone is in
the feed, 53 the pinned aircraft appearing, 54 the Drone feed row with a rejected token, 55 "no drones in feed" and the
drop-out to waiting, and 56 the replay with the targets list filtered to 10 nm of the aircraft. 57 is the old 240 dpi
profile on 0.3.3. v0.3.5 (`rc-plus-29`): 66 has Google TTS disabled, as on the RC Plus (no TTS engine), and the Voice row reads
"OK (bundled voice)" during the 1× replay with the new cadence. 67 has Google TTS enabled: "OK (Google TTS)", the pinned replay
in the 6 s close band, and the "passing, diverging" banner. 68 is Settings → Voice with **Test voice**. What the emulator played was
recorded and transcribed: `docs/voice-bundled-emulator-capture.txt`.

`docs/screenshots/` 01–26: emulator at 1920×1200 / 240 dpi (AVD `sentry-rc`, 1280×800 dp). **That profile was
wrong for the RC Plus.** 27–36 (0.3.2) used 320 dpi (about 960×600 dp), which was still not dense enough;
the controller behaves as 400 dpi, as above. 27–36 used AVD `rc-plus` at 320 dpi:
27 is 0.3.1 on it, reproducing the truncated buttons from the owner's photo of the real controller ("Tes", "Dron",
"Settin"; the controller itself showed "AR", "T", "Dr", "Sett"). 28–33 are 0.3.2 on the same AVD: disarmed (28), the
demo replay with callouts (29), Settings top and bottom in one column (30, 31), the Drone… picker (32), and
live-armed with DISARM (33). 34 is the old 240 dpi profile after the change (the wide layout is unchanged).
35 is the Android 10 (API 29) image at 320 dpi, which is what the RC Plus runs. 36 is font scale 1.3.
`docs/replay-logcat-*.txt`: the `Sentry` logcat from the replays, with every CALLOUT and SPEAK line
(`-selection-pattern-DEMO-1` = watching by pattern; `-controller-cylinders` = no pattern, cylinders around the
launch point; `-selection-live-stale-fallback` = the live selector on the emulator against a mock fleet feed).
Screenshots 21–26 cover v0.3: the pinned serial set with "Use the drone I'm watching now" (21) and the replay
watching DEMO-1 as this controller's aircraft (22). The updater test: a local 0.2.9 build finds the published v0.3.0
(23), downloads it and opens Android's installer (24), and after the in-place update says 0.3.0 is up to date, with
the pinned serial kept (25). 25 is the released 0.3.0: its status line still shows the message saved before the
update ("Sentry 0.3.0 is available"). 26 is the fix on `main`, where that line is derived from the current state. Screenshots 13–20 cover drone selection and controller protection; the "DEMO-2" and "DEMO-12 Smith"
callsigns in 13, 15, 18 and 20 came from a **mock** DroneSense feed on the emulator (nothing was flying).

## Layout

```
core/  pure Kotlin/JVM: Geo, CpaMath, AlertEngine, HealthMonitor, Parsers, TrafficMerger, Replay,
       Selection (DroneSelector: pinned serial else controller, Cylinder, KnownDrones), FleetStatus
       (why the Drone feed shows no drones), TargetDisplay (what the list and compass show),
       Update (SemVer, Releases JSON, UpdatePolicy), Cadence (callout repeat bands), CalloutQueue +
       ClipTimeline, VoiceGrammar (speech text -> clip ids), VoicePolicy (TTS or bundled), SystemPhrases (+ tests)
app/   SentryService (FGS, pollers, watchdog, replay), AlertVoice (TTS or bundled voice + tones + ducking), ClipVoice,
       Notifier (status + heads-up + update), MainActivity, SettingsActivity, DroneHistory,
       Updater (GitHub check, download, PackageInstaller session), UpdatePrompt, RadarView, BootReceiver,
       ScreenLayout (compact vs wide layout by width in dp; unit-tested)
app/src/main/assets/voice/     the bundled voice: <id>.ogg clips + manifest.tsv (generated)
tools/voicebank/               gen.py + phrases.txt + sample.txt: regenerates the bundled voice offline (Piper)
VERSION                        the one version source (0.3.5 -> versionCode 305)
.github/workflows/android.yml  every push: tests + debug APK artifact
.github/workflows/release.yml  tag v*: tests + signed release APK attached to the GitHub release
docs/  DESIGN.md, screenshots, replay logs
```
