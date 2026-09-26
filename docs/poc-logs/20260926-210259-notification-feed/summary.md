# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-210259-notification-feed
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 577 |
| listener connected | True |
| posted / removed | 44 / 1 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, UpdateIconSet, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.ACTION_POWER_DISCONNECTED, android.intent.action.ACTION_POWER_CONNECTED, miui.intent.action.SUB_SCREEN_ON |
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
- **Posture Gate decides the mode**: auto first-cast needs both gates open (DND off AND
  face-down). The proximity sensor is on-change with no adb path, so the run reads the
  committed posture and adapts: `mode=auto` runs the legs as designed; `mode=charging-held`
  means the phone physically lies FACE UP (gate closed) -- the banner legs then run on a
  Dashboard held by the CHARGING source (gate-exempt, ticket #57) and the two DND gate
  legs are SKIPPED (they need source=AUTO). Evidence for the physical reading: proximity
  last event 5.00 (far), gravity z=-9.80 (screen up), app line `posture up`; A/B in run
  20260926-203147: the same post cast NOTHING with the gate closed and cast immediately
  after a restart (default-open) until the next posture commit closed it again.
- **Settings open check**: `dumpsys window` prints `mCurrentFocus=` PER DISPLAY and the
  rear display's (null) line comes FIRST -- reading the first line reported "never took
  focus" for an activity that HAD resumed (run 20260926-203147 lost its settings legs
  this way). The open check now uses the top activity of display 0 instead, and every
  node lookup retries (the first dump after an activity transition can be pre-layout).
- **Foreign notifications**: any other app posting during a timing window replaces the
  feed content (latest-notification semantics) and can hide/re-time the banner; the
  step notes carry the interference lines when it happens. The legs cancel only their
  own shell notifications -- other apps' notifications are never touched.
- **Auto-dismiss tier ladder** (AutoDismissSteps): 5/10/15/30/60/120/300s + unlimited;
  the script walks it with the -/+ steppers and re-reads STATE after every tap.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (157,156 bytes)
- dumpsys-display.txt (347,726 bytes)
- dumpsys-window.txt (159,172 bytes)
- logcat-rearcue.txt (99,521 bytes)
- logcat-system-rear.txt (106,408 bytes)
- notification-feed.txt (2,576 bytes)
- notification-feed-logcat.txt (82,960 bytes)
- scenario-notes.md (2,849 bytes)
- screenshots\feed-01-banner-privacy-on.png (23,394 bytes)
- screenshots\feed-03-after-expiry-iconset.png (23,351 bytes)
- screenshots\feed-04-privacy-off-title-text.png (23,354 bytes)
- screenshots\main.png (714,023 bytes)
- screenshots\notes.txt (247 bytes)
- screenshots\rear.png (20,812 bytes)
- screenshots\settings-autodismiss-5s.png (237,775 bytes)
- session.md (310 bytes)
- state.txt (24,327 bytes)
- transcript.txt (34,153 bytes)
