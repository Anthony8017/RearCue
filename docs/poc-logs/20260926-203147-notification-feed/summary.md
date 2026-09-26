# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-203147-notification-feed
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 181 |
| listener connected | True |
| posted / removed | 45 / 2 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | True / True |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (Notification Feed, tickets #55 + #56 / spec 0007)

- **Post keys**: `cmd notification post -t "RearCue feed" <tag> <text>` runs as
  com.android.shell (allowlisted). A new tag = a new key = a real Post event; the same
  key would only be an UPDATE and produce no refresh (README warning).
- **Timer measurement**: markers go into the SAME RearCue tag (`log -t RearCue
  pc-feed-post-*`), so marker and app lines share one device clock -- no PC skew.
  Refresh verdict: Hide lands 8..14s after post2 AND >=12s after post1; a stale timer
  (hide ~ post1+10) fails both bounds.
- **Settings page**: AllowlistSettingsActivity is not exported (am start denied from
  shell), so the script walks MainActivity -> gear (content-desc) -> uiautomator dump.
  Every change is judged by the app own `feed-settings page ...` line + the STATE
  debug echo, never by the tap itself.
- **Rear screenshots**: `screencap -d 1` is rejected on this build (04-drive finding);
  the SurfaceFlinger id from `dumpsys display` uniqueId ("local:<id>") works and is
  what every rear shot here used.
- **Posture Gate**: read from STATE and recorded per leg (faceDown=true = gate open).
  The proximity sensor is on-change and cannot be driven over adb, so the gate state
  is whatever the phone physically is during the run -- recorded, never asserted.
- **Auto-dismiss tier ladder** (AutoDismissSteps): 5/10/15/30/60/120/300s + unlimited;
  the script walks it with the -/+ steppers and re-reads STATE after every tap.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (155,983 bytes)
- dumpsys-display.txt (347,933 bytes)
- dumpsys-window.txt (159,621 bytes)
- logcat-rearcue.txt (26,286 bytes)
- logcat-system-rear.txt (116,390 bytes)
- notification-feed.txt (2,157 bytes)
- notification-feed-logcat.txt (25,358 bytes)
- scenario-notes.md (1,533 bytes)
- screenshots\feed-01-banner-privacy-on.png (114,371 bytes)
- screenshots\feed-03-after-expiry-iconset.png (114,486 bytes)
- screenshots\feed-04-privacy-off-title-text.png (114,226 bytes)
- screenshots\feed-08-banner-after-dnd-off.png (114,239 bytes)
- screenshots\main.png (714,965 bytes)
- screenshots\notes.txt (249 bytes)
- screenshots\rear.png (114,907 bytes)
- session.md (310 bytes)
- state.txt (9,843 bytes)
- transcript.txt (36,236 bytes)
