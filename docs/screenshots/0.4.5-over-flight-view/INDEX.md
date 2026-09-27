# Sentry 0.4.5 over a full-screen flight app (RC Plus, emulated)

The two defects from the 0.4.4 flight-view set, re-shot on 0.4.5:
1. the "Bound aircraft lost / back" pop-ups (0.4.4 shots 17 / 18) were Android's white notification template;
2. the "Clear: move …" hint flipped NW -> SE -> NW during the WARNING pass.

Build: 0.4.5 **release** APK built locally with the release key (`./gradlew assembleRelease`, versionName 0.4.5,
versionCode 405), installed with `adb install -r` over 0.4.4 (settings kept). Not a published release (no tag).
Device: AVD `rc-plus-29` (1920x1200 at 400 dpi, about 768x480 dp, Android 10, font scale 1.0, landscape, night mode
OFF = the real controller's case), launched on port 5556 with `-memory 2048` (host swap was full).
Stand-in flight app, data and scripts: the same as `../0.4.4-over-flight-view/` (`flightview/`, `mock.py`, `scen.py`;
fully synthetic: drone DEMO-7 / serial 1581DEMO000000000007, the demo replay DEMO-1 vs N388KM with its synthetic
serial). Banner duration 30 s (prefs), as in 0.4.4. `shoot.sh` (wait for a log line, screencap + SystemUI dump) and
`pass.sh` (the WARNING-pass sequence below) are the helpers used here.

## Shots
| # | File | What | Trigger |
|---|---|---|---|
| 17 | `17-lost-headsup.png` | "Bound aircraft lost" pop-up: dark card, **amber** | `scen.py nodrone` (log `OWNSHIP_LOST`, +1 s) |
| 17b | `17b-lost-shade.png` | the same, pulled down (expanded), with the status card under it | `nodrone` again, +8 s, shade |
| 18 | `18-regain-headsup.png` | "Bound aircraft back · Drone position regained · watching DEMO-7": dark card, **green** | `scen.py empty` (log `OWNSHIP_REGAINED`, +1 s) |
| 19 | `19-status-armed-shade.png` | Status notification, expanded: dark card, grey-blue, our own **Disarm** / **Open Sentry** buttons | armed, no traffic, shade |
| 23 | `23-warning-pass-1-headsup-115257.png` | WARNING heads-up, "Clear: move NW" | replay 11:52:57 (+1.9 s) |
| 24 | `24-warning-pass-2-shade-115306.png` | WARNING in the shade at replay **11:53:06**, "Clear: move NW" (0.4.4 said "move SE" at this tick) | same pass, +9.5 s |
| 25 | `25-warning-pass-3-shade-115308.png` | WARNING in the shade at replay 11:53:08, "Clear: move NW" | same pass, +11.5 s |

The replay clock in each shade shot is the status card's "REPLAY 11:53:0x PDT". The WARNING pass was also captured
every second while the shade was open (OCR of each frame; 24 and 25 are two of them):

| Replay tick | Closest (banner) | Hint |
|---|---|---|
| 11:53:04 | 1,600 ft in 28 s | Clear: move NW |
| 11:53:05 | 1,600 ft in 27 s | Clear: move NW |
| 11:53:06 | 1,600 ft in 36 s | Clear: move NW (0.4.4: **move SE**) |
| 11:53:07 | 1,900 ft in 35 s | Clear: move NW |
| 11:53:08 | 1,800 ft in 34 s | Clear: move NW |
| 11:53:09 | 1,900 ft in 35 s | Clear: move NW |
| 11:53:10 | 1,900 ft in 34 s | Clear: move NW |

(11:53:06 is the tick where a new N388KM fix re-derives his track: the closest-approach time jumps from 27 s to 36 s.)

## Heights (`heights.txt`, SystemUI `contentHeight`, dp = px / 2.5)
- Housekeeping pop-up (lost / back), heads-up: **131 px = 52.4 dp** (the same as a traffic heads-up without buttons);
  expanded 147 px = 58.8 dp.
- Status notification: collapsed 131 px = 52.4 dp, expanded with its buttons 270 px = 108.0 dp.
- WARNING heads-up 239 px = 95.6 dp (unchanged from 0.4.4), expanded 377 px = 150.8 dp.

## Buttons
The status card's own buttons were tapped over the flight app: **Disarm** at (424, 586) px logged `DISARMED` and the
flight app stayed in front (`mResumedActivity` = Flight View); after re-arming, **Open Sentry** at (682, 586) px
brought up Sentry's MainActivity. Log: `logcat-sentry.txt`.

## Not shown
The update notice ("Sentry x.y.z available") needs a newer release on GitHub than the installed build, so it could not
be triggered here. It is built by the same `Notifier.infoCard` as the cards above (grey-blue), and `BannerColorsTest`
checks every notification builder in the app.

## Checks
OCR (`tesseract`) over every PNG: only synthetic identifiers (DEMO-7, 1581DEMO000000000007, N388KM, the replay's
synthetic serial). The swiftshader underline artifacts from earlier sets appear here too.
