# Sentry 0.4.3 fixes verified on the RC Plus (emulated)

Build: debug APK of 0.4.3 (versionName 0.4.3), installed after `pm clear`. Debug because the `set` hook (worker URL,
pinned serial) only exists in debug builds.
Device: AVD `rc-plus-29`, 1920x1200 at 400 dpi (about 768x480 dp, the RC Plus dp budget), Android 10, font scale 1.0.
"Other app" in front: Android Settings (stands in for DroneSense).
Data: `mock.py` + `scen.py` (fully synthetic worker: drone DEMO-7 / serial 1581DEMO000000000007, aircraft DEMO22 /
DEMO33, TFR 9/9999 in empty desert; nothing is proxied) and the demo replay (DEMO-1 vs N388KM, synthetic serial,
crossing variant). Same triggers as the 0.4.2 pass (`docs/screenshots/0.4.2-alerts/`). Line numbers refer to
`logcat-sentry.txt` (2026-09-25, device clock PDT).

| # | Bug | File | Triggered by | Result | Logcat |
|---|---|---|---|---|---|
| 01 | 1 zone entry | 01-zone-entry-tfr-1s.png | `scen.py zone_out`, then `zone_in` (DEMO33 1.3 -> 1.8 nm E into TFR 9/9999) | "▣ ENTERING TFR 9/9999 · DEMO33" on screen 1 s after the post | line 14; no `BANNER retitle` anywhere in the log |
| 02 | 1 zone entry | 02-zone-entry-tfr-4s.png | same, 4.3 s after the post | still "▣ ENTERING TFR 9/9999" | - |
| 03 | 2 first bind | 03-bound-aircraft-acquired-first-bind.png | `scen.py empty` (DEMO-7 appears) | "Bound aircraft acquired" | line 10 |
| 04 | 3 long text | 04-bound-aircraft-lost.png | `scen.py nodrone` | "Drone position lost; controller cylinders are the fallback", no ".." | line 17 |
| 05 | 2 regain | 05-bound-aircraft-back-one-message.png | `scen.py empty` after the 30 s fallback | ONE pop-up, "Bound aircraft back · Drone position regained · watching DEMO-7"; no SELECTION in that tick | line 20 |
| 06 | 3 long text | 06-internet-offline.png | `svc wifi disable` + `svc data disable` | "Cloud traffic, drone feed and TFR updates paused until it is back" (station off, so it is not named), no ".." | line 36 |
| 07 | - | 07-internet-back.png | `svc wifi enable` + `svc data enable` | "Cloud traffic and the drone feed resume" | line 38 |
| 08 | 3 long text | 08-sentry-restarted.png | `kill -9` of the app process (root), START_STICKY restart | "Stopped unexpectedly and restarted itself; armed and watching again", no ".." | line 50 |
| 09 | 3 long text | 09-sentry-armed-after-restart.png | `adb install -r` while armed (MY_PACKAGE_REPLACED, same path as power-on) | "Re-armed after a controller restart or app update; watching again", no ".." | line 73 |
| 10 | 4 collision | 10-collision-risk-crossing-replay.png | replay `--ez crossing true`, 1x, pinned 1581F7K3C251F00C9B34 | COLLISION RISK popup at 11:52:57 | lines 177-216 |
| 11 | 6 plural | 11-status-notification-1-target.png | `scen.py advisory`, shade pulled | "Bound to 1581DEMO000000000007 · DEMO-7 · 1 target · sounds on" | - |
| 12 | 6 radar | 12-main-radar-ring-labels.png | main screen, rings 3 / 1 / 0.5 nm | "1 nm" up-right, "0.5 nm" moved down-left with a panel backing: no overlap | - |
| 13 | 5 copy | 13-settings-advisory-copy.png | Settings, top | "Advisory = inside 3 mi (Standard style: one short tone + banner on entry, repeats banner-only; Quiet: silent)" | - |

Crossing replay (bug 4), lines 177-216: COLLISION RISK escalation 11:52:57, then a COLLISION RISK tone every 3 s
(11:53:00, :03, :06, ... :42) with no WARNING DOWNGRADE in between (0.4.2 logged `11:53:05 WARNING TRAFFIC/DOWNGRADE`),
PASSING at 11:53:45, CLEAR 11:54:54. Lines 129-168 are an identical earlier run of the same replay.

Notes
- Lines 110-117: a first replay started while the pinned serial was still the mock's DEMO-7, so it ran in controller
  (cylinder) mode; it was stopped and re-run with the replay's synthetic serial pinned.
- After "Sentry armed after restart" with the bound drone in the feed, "Bound aircraft acquired" posts about 1 s later
  and replaces the restart pop-up on screen (lines 28/32, 88/92, 102/106). 09 was captured with the drone absent. Not
  changed in 0.4.3 (the restart starts a new session, so the bind is a first bind).
- The faint underlines under some letters are a swiftshader rendering artifact of the emulator, not part of the app.
