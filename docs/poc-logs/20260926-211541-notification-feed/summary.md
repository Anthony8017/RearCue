# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-211541-notification-feed
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 822 |
| listener connected | True |
| posted / removed | 56 / 9 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, ExitDashboard, UpdateIconSet |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | android.intent.action.ACTION_POWER_DISCONNECTED, android.intent.action.ACTION_POWER_CONNECTED, miui.intent.action.SUB_SCREEN_OFF, miui.intent.action.SUB_SCREEN_ON |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### notification-feed.txt

```
show                : pass=True  -- ShowFeedBanner(com.android.shell): True; charging-held: castSource=CHARGING, LaunchDashboard delta=0 (must be 0: screen already up); owner=dashboard: True; privacy default: True
refresh-restart     : pass=True  -- ShowFeedBanner after post2: True; hide-post2=10.5s (want 8..14, timer restarted); hide-post1=17.1s (want >=12)
expiry-iconset      : pass=True  -- owner after expiry: dashboard; ExitDashboard 2->2 (delta 0 = banner expiry alone does not withdraw); iconSet keeps com.android.shell: True
privacy-off         : pass=True  -- 5min tier set: True; banner up before toggle: True; privacy=false line: True; STATE privacy: False; ShowFeedBanner re-issued: True
autodismiss-5s      : pass=True  -- tier 5000ms set: True; banner up: True; hide-post=5.3s (want 4..8)
persist-restart     : pass=True  -- first-read line after restart: True; STATE privacy=False autoDismiss=5000ms
clear-dismiss       : pass=True  -- banner up before clear: True; HideFeedBanner 0.1s after the clear marker (want <=3); charging holds: owner still dashboard right after the clear: True; charging released: disconnect line True, handed back True
dnd-withdraw        : pass=SKIP  -- SKIPPED: posture gate closed (phone physically face-up; proximity is an on-change sensor with no adb path). The banner is held by the CHARGING source, which DND cannot withdraw by design (ticket #57), so withdraw/recast need source=AUTO -> needs the phone face-down
dnd-recast          : pass=SKIP  -- SKIPPED: posture gate closed (phone physically face-up; proximity is an on-change sensor with no adb path). The banner is held by the CHARGING source, which DND cannot withdraw by design (ticket #57), so withdraw/recast need source=AUTO -> needs the phone face-down
overall             : FEED-PASS (7 legs judged, all pass: show / refresh-restart / expiry-iconset / privacy-off / autodismiss-5s / persist-restart / clear-dismiss) / SKIPPED (2: dnd-withdraw, dnd-recast -- environment, see scenario notes)
```

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
- dumpsys-activities.txt (157,224 bytes)
- dumpsys-display.txt (347,673 bytes)
- dumpsys-window.txt (159,170 bytes)
- logcat-rearcue.txt (152,549 bytes)
- logcat-system-rear.txt (106,639 bytes)
- notification-feed.txt (2,695 bytes)
- notification-feed-logcat.txt (137,183 bytes)
- scenario-notes.md (2,849 bytes)
- screenshots\feed-01-banner-privacy-on.png (22,809 bytes)
- screenshots\feed-03-after-expiry-iconset.png (20,811 bytes)
- screenshots\feed-04-privacy-off-title-text.png (23,278 bytes)
- screenshots\main.png (713,256 bytes)
- screenshots\notes.txt (247 bytes)
- screenshots\rear.png (17,654 bytes)
- screenshots\settings-autodismiss-5s.png (236,465 bytes)
- session.md (310 bytes)
- state.txt (23,131 bytes)
- transcript.txt (24,593 bytes)
