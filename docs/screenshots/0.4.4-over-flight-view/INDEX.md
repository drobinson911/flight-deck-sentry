# Sentry 0.4.4 alerts over a full-screen flight app (RC Plus, emulated)

The owner's question: what does every alert look like while a flight app fills the controller screen?
The real flight app can't run on the emulator, so a stand-in called **Flight View** fills the screen instead, and every
Sentry banner is shown over it.

Build: the signed **0.4.4 release APK** from the GitHub release (`flight-deck-sentry.apk`, sha256 checked, signer
`07d612bf…51dc` = the release key, versionCode 404). There are no debug hooks: settings were written into the app's
`shared_prefs` with `adb root`, and the service was driven with `am start-foreground-service` (ARM, REPLAY
`--ez crossing true|false`, REPLAY_STOP).
Device: AVD `rc-plus-29`, 1920x1200 at 400 dpi (about 768x480 dp), Android 10, font scale 1.0, landscape.
Night mode: OFF for everything (the real controller's case), plus night mode ON (`cmd uimode night yes`) for WARNING and
COLLISION RISK (`-night` files).
Banner duration: 30 s (not the default 5 s) so the banner is still in the shade when it's pulled down. The heads-up
layout doesn't change with the duration.

## Flight View (the stand-in flight app)
`flightview/`: `render.py` draws one 1920x1200 frame with PIL: a synthetic oblique aerial view (value-noise terrain
projected onto a ground plane, haze, a dirt road) and a generic HUD (REC 00:12:41, GPS 17, RC bars, BAT 71%,
heading tape 247°, reticle and pitch ladder, ALT 312 ft AGL / DIST 0.4 nm / SPD 18 kt, map inset, RTH / PAUSE / PHOTO).
No photos, logos or real names. `FlightViewActivity.java` shows the frame edge-to-edge in sticky immersive mode (status
and navigation bars hidden, screen kept on). `build.sh` builds the APK without Gradle (aapt2, javac, d8, apksigner with
the local debug key) in a few seconds. The image is static, so the HUD doesn't move between shots.

## Data (synthetic only)
- `mock.py` + `scen.py`: a fully synthetic worker on the host (nothing proxied), drone DEMO-7 (serial
  1581DEMO000000000007) in empty desert, aircraft DEMO22 / DEMO33 / DEMO44, TFR 9/9999. This run added `track_in` /
  `track_off` (DEMO44 heading at the drone from 3.2 nm, then turning away) and used `nodrone` for lost/regain.
- The built-in demo replay (DEMO-1 vs N388KM, synthetic serial pinned) for TRACK, WARNING, PASSING and, with
  `crossing true`, COLLISION RISK.

## Shots
| # | File | What | Trigger |
|---|---|---|---|
| 01 | `01-advisory-headsup.png` | ADVISORY heads-up | `scen.py advisory` |
| 02 | `02-caution-headsup.png` | CAUTION heads-up | `scen.py caution` |
| 03 | `03-zone-headsup.png` | Zone entry (ENTERING TFR) heads-up | `scen.py zone_out`, then `zone_in` |
| 04 | `04-zone-shade.png` | Zone entry, shade pulled down | same, about 9 s later |
| 05 | `05-track-headsup.png` | TRACK heads-up | replay 11:52:14 |
| 06 | `06-track-shade.png` | TRACK, shade | same, about 9 s later |
| 07 | `07-warning-headsup.png` | WARNING heads-up | replay 11:52:57 |
| 08 | `08-warning-shade.png` | WARNING, shade | same, about 9 s later |
| 09 | `09-warning-headsup-night.png` | WARNING heads-up, night mode ON | replay 11:52:57 |
| 10 | `10-warning-shade-night.png` | WARNING, shade, night mode ON | same, about 9 s later |
| 11 | `11-passing-shade.png` | PASSING (in-place update), shade | replay 11:53:40 |
| 12 | `12-collision-headsup.png` | COLLISION RISK heads-up (no buttons) | crossing replay 11:52:57 |
| 13 | `13-collision-shade.png` | COLLISION RISK, shade | same, about 9 s later |
| 14 | `14-collision-headsup-night.png` | COLLISION RISK heads-up, night mode ON | crossing replay 11:52:57 |
| 15 | `15-collision-shade-night.png` | COLLISION RISK, shade, night mode ON | same, about 9 s later |
| 16 | `16-clear-shade.png` | "○ CLEAR · DEMO44" (no longer a factor), shade | `track_in`, then `track_off` |
| 17 | `17-lost-headsup.png` | "Bound aircraft lost" pop-up | `scen.py nodrone` |
| 18 | `18-regain-headsup.png` | "Bound aircraft back · Drone position regained" pop-up | drone back in the feed |
| 19 | `19-status-armed-shade.png` | Persistent status notification (armed), shade | armed, no traffic |
| 20 | `20-quiet-before-headsup.png` | CAUTION heads-up, just before tapping "Quiet 5 min" | `scen.py caution` |
| 21 | `21-quiet-tapped-headsup.png` | 1.2 s after tapping "Quiet 5 min" at (856, 255) px | log: `ACTION Quiet: traffic sounds off 5 min (banners still update)` |
| 22 | `22-quiet-shade.png` | Status notification during Quiet: "… · Quiet 4 min 5 s left" | shade, about 55 s after the tap |
| - | `contact-sheet.png` | Every heads-up shot, 2 columns, labelled | `flightview/contact_sheet.py` |

## What the pictures show
- The heads-up draws over the immersive flight app. While it's on screen, Android also briefly shows its own status bar,
  which overlaps the top row of the flight app ("Flight Deck Sentry" plus the system icons over REC / BAT). That's the
  platform, not Sentry.
- The heads-up covers the top of the flight view (about y 80-320 px with buttons, 80-190 px without): the heading tape and
  the top of the video. The telemetry block, reticle, map inset and RTH / PAUSE stay visible.
- Night mode doesn't change the heads-up (Sentry paints its own dark card). Only the shade around it goes dark.
- **PASSING has no heads-up of its own.** It's a silent in-place update of the WARNING notification (plus its sound), so
  it only shows as a heads-up if the WARNING heads-up is still on screen. Here it's shown in the shade (11).
- **"Clear" has no heads-up either.** "No longer a factor" is a silent in-place update (16). A plain CLEAR / track lost
  removes the notification. The only pop-ups for "coming back" are the drone-position lost/regained pair (17, 18).
- **Tapping "Quiet 5 min" changes nothing on the heads-up itself** (20 and 21 are identical). The result shows in the
  status notification ("Quiet 4 min 5 s left", 22) and in the log.
- The Clear hint can differ between the heads-up and the shade within a few seconds as the geometry changes: WARNING
  heads-up 11:52:57 "Clear: move NW", day shade about 11:53:05 "Clear: move SE", night shade at the same moment "move NW".

## Checks
- OCR (`tesseract`) over every PNG: only synthetic identifiers (DEMO-7, DEMO22/33/44, 1581DEMO…, N388KM, TFR 9/9999).
  The forbidden-strings pattern from CI finds nothing in the tracked text.
- The swiftshader underline artifacts from earlier sets appear here too. "‼" renders as "!!".
