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
