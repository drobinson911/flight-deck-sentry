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

Replaying the real data from that day, Sentry says:

| Replay time (PDT) | Severity | Spoken |
|---|---|---|
| 11:52:35 | advisory | "Traffic, N388KM, southwest, 3.0 miles, 100 above, converging." |
| 11:52:57 | **warning** | "Warning. Traffic, N388KM, southwest, 1.9 miles, 100 below, converging, closest 2,700 feet in 40 seconds." |
| 11:53:17 | **warning** | "Warning. Traffic, N388KM, southwest, 5,900 feet, 200 below, converging, closest 1,200 feet in 18 seconds." |
| 11:53:23 | **warning** | "Traffic entering TFR 0/0000, N388KM, southwest, 4,700 feet, 300 below, converging." |
| *11:53:40* | | *(closest approach, 0.24 nm)* |
| 11:54:54 | info | "N388KM clear, diverging." |

The first warning comes **43 seconds before the pass**. The same run with N388KM's altitude
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
| Drone position (fallback) | Manual pin (lat/lon/alt) or this controller's GPS | 1 s | Used **only** when no fleet position is fresh. Shown and spoken: "Drone feed lost, using manual position". |
| Traffic A: truck station | `GET http://<station>:8080/data/aircraft.json` (Overwatch, readsb shape) | 1 s | Typed URL **and** Overwatch UDP beacon auto-discovery (port 41120). Off by default. "Station link lost / regained" is spoken. |
| Traffic B: cloud | `GET {worker}/api/live/adsb` (browser User-Agent) | 5 s | About 1,000 aircraft nationwide, filtered to 30 nm around the drone. |
| TFRs | `GET {worker}/api/tfrs` | 10 min | GeoJSON; `NOTAM_NUMBER`, `_ALT_L/H_VAL/UOM/CODE` (ALT=MSL, HEI=AGL, FL). Last good copy cached on disk. |
| Geofences | Settings | — | Circle (centred on the drone or on a fixed point) + polygons imported from a GeoJSON file. |

`{worker}` defaults to `https://uas-app.drobinson911.workers.dev`. Traffic is merged per ICAO
hex, and the fresher **position** wins. Ages come from each feed's *relative* fields
(`seen_pos`, `_ageMs`), so a laptop with a wrong clock can't make stale data look fresh.

## Alert rules (pure Kotlin, `core/`, unit-tested)

- **Rings around the drone:** 3 nm advisory, 1 nm caution, 0.5 nm warning, within ±2,000 ft (all configurable).
- **Predictive:** if the closest point of approach within 60 s falls inside the 0.5 nm ring and inside the vertical band, a **warning is issued now**.
- **TFR / geofence entry:** a transition from outside to inside, when the aircraft's altitude is inside the zone's floor/ceiling. **Unknown altitude counts as inside** and is spoken as "altitude unknown". Only TFRs within 10 nm of the drone are watched.
- **What is spoken:** cardinal direction from the drone (north … northwest); distance in nautical miles to one decimal, or in feet under 1 nm; "N hundred above/below"; converging / diverging / passing (from the range rate). Callsigns are spelled out ("N 3 8 8 K M") so they carry over rotor and road noise.
- **Tones:** a different tone per severity plays before the speech.
- **Rate limits:** a target is re-announced at most every 20 s (30 s for advisory), immediately when it escalates, and **once** when it clears ("N388KM clear, diverging"). A target that is already diverging is not re-announced every 20 s.
- **Stale data is never announced:** targets whose `seen_pos` is over 30 s old are dropped. Sentry says "Traffic data stale" once when all traffic sources are more than 30 s old, "Drone position lost" when the drone's position is more than 15 s old (then "regained"), and "track lost" or "on the ground" for a target it had been calling.
- **Ground mode at speed:** `alt_baro:"ground"` with a speed of 50 kt or more is treated as **airborne, altitude unknown**. It is never dropped as ground traffic. (This is the N388KM lesson.)
- **Altitude:** `alt_geom` is used when present. Otherwise `alt_baro` + 300 ft, labelled "estimated" (≈) on screen. The drone's altitude is MSL from the feed.

## Install on the RC Plus

1. Get an APK: from the **Actions** tab (artifact `flight-deck-sentry-debug-apk`), or run `./gradlew assembleDebug` locally.
   A locally built APK embeds the fleet token from `secrets.properties`. The CI APK does not.
2. Enable USB debugging on the RC Plus (Settings → About → tap Build number 7x → Developer options).
3. `adb install -r app-debug.apk`. Alternatively, copy the APK to the controller and open it with a file manager (allow "install unknown apps").
4. Open **Flight Deck Sentry** → **Settings**. Paste the fleet token if the APK doesn't have one built in. Set **Drone callsign** (e.g. `DEMO-1`) if more than one drone may be airborne. Optionally turn on the truck station.
5. Tap **ARM**. Allow notifications. Accept the **battery-optimisation exemption** so Android doesn't throttle Sentry with the screen off.
6. Tap **Test callout** to set the volume. Then switch to DroneSense: Sentry keeps running and its banners appear over DroneSense.

## Settings

Fleet token (paste from the clipboard) · drone callsign filter · worker URL · truck station on/off + address + beacon auto-discover · cloud on/off · traffic radius · advisory/caution/warning rings · vertical band · baro correction · predictive look-ahead · TFR watch distance · voice on/off + volume · manual position (off / pinned / controller GPS) · circle geofence · GeoJSON geofence import · re-arm after reboot · battery exemption · **Replay: demo encounter** (1× / 4×, optional public-feed view).

## Replay mode

Settings → **Replay: demo encounter** plays the real DEMO-1 and N388KM tracks and the
TFR 0/0000 polygon, bundled in `app/src/main/assets/replay/`, through the **same engine,
voice, and notification path** used live. A replay clock is shown in PDT. From adb
(debug builds only):

```
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 1
adb shell am start -n com.uasflightdeck.sentry/.MainActivity --es sentry_action replay --ef speed 4 --ez cloud_view true
```

## Build & test

```
./gradlew testDebugUnitTest   # runs :core:test (engine, geodesy, CPA, parsers, demo replay) + app tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, compileSdk 34, minSdk 26, targetSdk 33, AGP 8.5.2, Kotlin 1.9.24, Gradle 8.14.3.
Copy `secrets.properties.example` to `secrets.properties` (gitignored) to bake in a default fleet token.

## Screenshots

`docs/screenshots/`: emulator at 1920×1200 / 240 dpi, which matches the RC Plus panel.
`docs/replay-logcat-*.txt`: the `Sentry` logcat from the replays, with every CALLOUT and SPEAK line.

## Layout

```
core/  pure Kotlin/JVM: Geo, CpaMath, AlertEngine, HealthMonitor, Parsers, TrafficMerger, Replay (+ tests)
app/   SentryService (FGS, pollers, watchdog, replay), AlertVoice (TTS+tones+ducking),
       Notifier (status + heads-up), MainActivity, SettingsActivity, RadarView, BootReceiver
docs/  DESIGN.md, screenshots, replay logs
```
