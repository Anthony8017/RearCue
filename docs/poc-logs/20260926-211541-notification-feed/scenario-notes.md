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
