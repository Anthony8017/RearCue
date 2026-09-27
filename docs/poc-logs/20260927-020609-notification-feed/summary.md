# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260927-020609-notification-feed
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 703 |
| listener connected | True |
| posted / removed | 34 / 7 (allowlist hits: com.android.shell / com.android.shell) |
| effects | LaunchDashboard, UpdateIconSet, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.ACTION_POWER_DISCONNECTED, miui.intent.action.SUB_SCREEN_ON, android.intent.action.ACTION_POWER_CONNECTED |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### notification-feed.txt

```
show                : pass=False -- ShowFeedBanner(com.android.shell): False; charging-held: castSource=, LaunchDashboard delta=0 (must be 0: screen already up); owner=dashboard: False; privacy default: False
refresh-restart     : pass=False -- ShowFeedBanner after post2: False; hide-post2=s (want 8..14, timer restarted); hide-post1=s (want >=12)
expiry-iconset      : pass=False -- owner after expiry: native; ExitDashboard 0->0 (delta 0 = banner expiry alone does not withdraw); iconSet keeps com.android.shell: False
privacy-off         : pass=False -- 5min tier set: False; banner up before toggle: True; privacy=false line: False; STATE privacy: False; ShowFeedBanner re-issued: False
autodismiss-5s      : pass=False -- tier 5000ms set: False; banner up: True; hide-post=7.3s (want 4..8)
persist-restart     : pass=False -- first-read line after restart: False; STATE privacy=False autoDismiss=7000ms
clear-dismiss       : pass=False -- banner up before clear: False; HideFeedBanner -6s after the clear marker (want <=3); charging holds: owner still dashboard right after the clear: True; charging released: disconnect line True, handed back True
dnd-withdraw        : pass=SKIP  -- SKIPPED: posture gate closed (phone physically face-up; proximity is an on-change sensor with no adb path). The banner is held by the CHARGING source, which DND cannot withdraw by design (ticket #57), so withdraw/recast need source=AUTO -> needs the phone face-down
dnd-recast          : pass=SKIP  -- SKIPPED: posture gate closed (phone physically face-up; proximity is an on-change sensor with no adb path). The banner is held by the CHARGING source, which DND cannot withdraw by design (ticket #57), so withdraw/recast need source=AUTO -> needs the phone face-down
overall             : FEED-RUN-INVALID (preflight: zen/rear/listener/channel/empty Icon Set/default settings/charging hold not all up)
```

## scenario notes (Notification Feed, tickets #55 + #56 / spec 0007)

- **Post keys**: `cmd notification post -t "RearCue feed" <tag> <text>` runs as
  com.android.shell (allowlisted). A new tag = a new key = a real Post event. The same
  key with a CHANGED body is a content update: the repository reports Updated, the
  banner refreshes and re-timers, and the Icon Set does not re-count (spec 0007 story
  1/4 review fix). The same key with the SAME body stays a no-op. The legs therefore
  always post under a UNIQUE tag -- a new key keeps the Post path under test.
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
- **Auto-dismiss is a range-complete numeric input** (AutoDismissPolicy, review fix):
  any integer >= 5s plus a separate unlimited row -- 7s/10min/2h are all reachable,
  the old 8-tier ladder is gone. The script sets a value by (1) leaving the unlimited
  row when the entry sits there (the field is disabled by design), (2) tapping the unit
  chip that entryOf would render for the target (hours > minutes > seconds), (3)
  focusing the field (the app select-alls on focus) and committing the digits in one
  `input text`, then (4) hiding the keyboard when it really was up. Every value is
  judged from STATE + the `page autoDismiss=<n>ms` line, never from the tap.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (66,896 bytes)
- dumpsys-display.txt (291,018 bytes)
- dumpsys-window.txt (107,388 bytes)
- logcat-rearcue.txt (119,329 bytes)
- logcat-system-rear.txt (111,606 bytes)
- notification-feed.txt (2,624 bytes)
- notification-feed-logcat.txt (104,092 bytes)
- scenario-notes.md (3,622 bytes)
- screenshots\feed-01-banner-privacy-on.png (158,434 bytes)
- screenshots\feed-03-after-expiry-iconset.png (158,911 bytes)
- screenshots\feed-04-privacy-off-title-text.png (24,063 bytes)
- screenshots\main.png (715,247 bytes)
- screenshots\notes.txt (247 bytes)
- screenshots\rear.png (16,557 bytes)
- screenshots\settings-autodismiss-5s.png (92,698 bytes)
- session.md (310 bytes)
- state.txt (8,978 bytes)
- transcript.txt (32,192 bytes)
