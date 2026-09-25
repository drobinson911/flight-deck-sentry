# Sentry 0.4.2 alerts as they appear on the RC Plus (emulated)

Build: debug APK of tag v0.4.2 (4c6401a), versionName 0.4.2, installed after `pm clear`. The signed release APK
was not used because the `set` hook (worker URL, pinned serial) only exists in debug builds.
Device: AVD `rc-plus-29`, `wm size` 1920x1200, `wm density` 400 (about 768x480 dp, the RC Plus dp budget), Android 10, font scale 1.0.
"Other app" in front: Android Settings (stands in for DroneSense). Sentry's own screen is never in front for the banners.
Data: replay = the demo encounter (DEMO-1 vs N388KM, synthetic serial). Live alerts use `mock.py` + `scen.py`
(fully synthetic worker: drone DEMO-7 / serial 1581DEMO000000000007, aircraft DEMO22 / DEMO33, TFR 9/9999, in empty desert;
nothing is proxied to the real fleet feed). Timestamps refer to `logcat-sentry.txt` (2026-09-25, device clock PDT).

| # | Alert | File | Triggered by | Logcat |
|---|---|---|---|---|
| 01 | ADVISORY (3 nm ring entry) | 01-advisory.png | mock: DEMO22 stationary 2.5 nm SW, in band | 12:49:17.837 |
| 02 | CAUTION (1 nm ring) | 02-caution.png | mock: DEMO22 moved to 0.8 nm | 12:49:37.850 |
| 03 | TRACK ALERT "in 1 min 19 s", Got it / Ignore / Quiet 5 min | 03-track-alert.png | replay, speed 1 | 12:40:33.506 |
| 04 | WARNING (predicted) with "Clear: move NW" | 04-warning-predicted.png | replay | 12:41:16.541 |
| 05 | WARNING (1/2 nm ring) | 05-warning-half-nm-ring.png | mock: DEMO22 at 0.4 nm, stationary (no Clear hint: not closing) | 12:50:03.873 |
| 06 | COLLISION RISK (4 lines, no buttons) | 06-collision-risk.png | replay `--ez crossing true` | 12:45:31.684 |
| 07 | PASSING (grey, diverging) | 07-passing.png | replay (in-place update of the WARNING banner) | 12:41:59.574 |
| 08 | CLEAR | 08-clear-banner-removed.png | replay; CLEAR = banner CANCEL, so nothing shows over the other app | 12:43:13.623 |
| 09 | Zone entry (TFR) | 09-zone-entry-tfr-retitled.png | mock: DEMO33 moved 1.3 -> 1.8 nm E into TFR 9/9999. Shows ADVISORY: see note | 12:51:19.930 (+ retitle 12:51:19.949) |
| 10 | Internet offline | 10-internet-lost.png | `svc wifi disable` + `svc data disable` | 12:52:00.958 |
| 11 | Internet back | 11-internet-back.png | `svc wifi enable` + `svc data enable` | 12:52:44.992 |
| 12 | Waiting for this controller's aircraft (screen-only; status notification) | 12-waiting-for-this-controllers-aircraft-status.png | armed, pinned serial absent from mock feed | 12:48:10.791 |
| 13 | Bound aircraft acquired | 13-bound-aircraft-acquired.png | mock: DEMO-7 appears in the feed | 12:49:06.828 |
| 14 | Bound aircraft lost ("Drone position lost") | 14-bound-aircraft-lost.png | mock: DEMO-7 removed from the feed | 12:55:38.107 |
| 15 | Bound aircraft back ("Drone position regained") | 15-bound-aircraft-back.png | mock: DEMO-7 restored | 12:56:10.134 |
| 16 | Sentry restarted | 16-sentry-restarted.png | `kill -9` of the app process (root), START_STICKY restart | 12:56:31.914 |
| 17 | Sentry armed after restart | 17-sentry-armed-after-restart.png | armed, then `adb reboot` (over the launcher, as after a power cycle) | 12:57:11.605 |
| 18 | Sentry sounds on | 18-sentry-sounds-on.png | Quiet (service QUIET action), waited the full 5 min | 13:03:42.986 |
| 19 | Status notification (Disarm / Open Sentry) | 19-status-notification-disarm-open.png | shade pulled while armed + bound | - |
| 20 | Main screen armed, waiting | 20-main-armed-waiting.png | reference | - |
| 21 | Main screen armed, Resources row | 21-main-armed-resources-row.png | Sources column scrolled | - |
| 22 | Settings top (rings / protected volume) | 22-settings-top-rings.png | reference | - |

Notes
- 09: the zone-entry banner ("ENTERING TFR 9/9999 ... Clear: move W") is posted and then overwritten 19 ms later in the
  same tick by the per-second countdown refresh (`BANNER retitle DEMO33: ENTERING TFR 9/9999 -> ADVISORY`), so the
  pilot sees an ADVISORY banner, not the zone entry. The screenshot shows what the RC would actually display.
- 15: on regain two housekeeping popups post in the same tick ("Bound aircraft acquired" and "Bound aircraft back");
  only the second is visible.
- 18 (and 15 in an earlier capture): faint underlines under some letters in the title are a swiftshader rendering
  artifact of the emulator, not part of the app.
