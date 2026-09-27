# Sentry 0.4.4 traffic banners on the RC Plus (emulated)

0.4.4 is a presentation-only release: the banner text had to be readable and the heads-up shorter. The alert
logic, tiers, cadence and sounds did not change.

Build: debug APK of 0.4.4 (versionName 0.4.4, versionCode 404), because the `set` hook (worker URL, pinned serial)
only exists in debug builds.
Device: AVD `rc-plus-29`, 1920x1200 at 400 dpi (about 768x480 dp, the RC Plus dp budget), Android 10, font scale 1.0.
"Other app" in front: Android Settings (stands in for DroneSense).
Themes: every banner is shown with the system in the default (light) theme and forced dark (`cmd uimode night yes`).
Data: the demo replay (DEMO-1 vs N388KM, synthetic serial; `--ez crossing true` for COLLISION RISK) and `mock.py` +
`scen.py` (fully synthetic worker, nothing proxied: drone DEMO-7, aircraft DEMO22 / DEMO33, TFR 9/9999 in empty
desert) for ADVISORY, CAUTION and zone entry.
Expanded views: taken with the banner duration set to 30 s so the banner is still in the shade after the heads-up
times out (Android 10 auto-expands the top notification when the shade is pulled). All heads-ups use the default 5 s.

| # | Level | Heads-up (light / dark) | Expanded (light / dark) | Trigger |
|---|---|---|---|---|
| 01-04 | ADVISORY (3 nm ring) | 01 / 02 | 03 / 04 | `scen.py advisory` (DEMO22 2.5 nm SW) |
| 05-08 | CAUTION (1 nm ring) | 05 / 06 | 07 / 08 | `scen.py caution` (DEMO22 0.8 nm) |
| 09-12 | Zone entry (TFR) | 09 / 10 | 11 / 12 | `scen.py zone_out`, then `zone_in` (DEMO33 into TFR 9/9999) |
| 13-16 | TRACK | 13 / 14 | 15 / 16 | replay, 11:52:14 |
| 17-20 | WARNING | 17 / 18 | 19 / 20 | replay, 11:52:57 |
| 21-24 | PASSING | 21 / 22 | 23 / 24 | replay, in-place update after the pass |
| 25-28 | COLLISION RISK (no buttons) | 25 / 26 | 27 / 28 | replay `--ez crossing true`, 11:52:57 |

Before / after (WARNING heads-up, same replay moment):
- `before-0.4.3-warning-headsup-light.png`, `before-0.4.3-warning-headsup-dark.png`: 0.4.3, 132.8 dp tall.
- `after-0.4.4-warning-headsup-light.png`, `after-0.4.4-warning-headsup-dark.png`: 0.4.4, 95.6 dp tall.
- `before-after-warning-headsup-light.png`: the two cropped and stacked.

Heights: `heights.txt` (SystemUI dump per screenshot). Heads-up 332 px = 132.8 dp in 0.4.3 -> 239 px = 95.6 dp in
0.4.4 (with buttons), 131 px = 52.4 dp without buttons (PASSING, COLLISION RISK).

Buttons: the heads-up and expanded buttons are Sentry's own now. `logcat-sentry.txt` (last block): tapping "Got it" on
the WARNING heads-up at (410, 255) px logged `ACTION Got it: N388KM repeats muted 60 s` 34 ms later.

Why 0.4.3 was unreadable on the controller: its body lines used the platform notification text appearance
(`TextAppearance.Compat.Notification.Line2` -> `@android:style/TextAppearance.Material.Notification`), whose colour on
Android 10 is `@color/notification_secondary_text_color_light` = #de000000 (black), switching to white only when the
system itself is in night mode. The stock emulator switches both the surface and the text together (the 0.4.3 "before"
dark screenshot is readable), so it can't show the controller's failure: a dark heads-up surface while the system is
not in night mode, which gives black text on dark. 0.4.4 paints its own opaque dark card (#10161D) and gives every
line an explicit colour, so the system theme no longer matters: light and dark screenshots are identical inside the
card.

Notes
- The faint underlines under some letters are a swiftshader rendering artifact of the emulator, not part of the app.
- "‼" renders as "!!" in the emulator's font.
