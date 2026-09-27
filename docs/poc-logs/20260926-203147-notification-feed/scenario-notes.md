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
