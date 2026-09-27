## scenario notes (Charging Animation, ticket #57 / spec 0007)

- **How the plug was triggered (software only)**: `am broadcast -a
  android.intent.action.ACTION_POWER_CONNECTED` is refused on this build
  (`SecurityException: Permission Denial ... from pid=..., uid=2000` -- protected
  broadcast, captured in the session transcript). The battery service emits the two
  broadcasts itself when its plug state changes, so the legs drive it with
  `dumpsys battery unplug` (DISCONNECTED) and `dumpsys battery set ac 1`
  (CONNECTED, only after an unplug -- a bare `set ac 1` changes no plug type while
  the phone is already USB-powered, therefore no broadcast). `dumpsys battery
  reset` restores the real state in the restore step (mandatory).
- **Every leg is judged from the app own `power-connected` / `power-disconnected`
  refresh lines + the STATE echo (`castSource=CHARGING`), never from shell exit codes.
- **Posture Gate**: the proximity sensor is on-change with no adb path, so the run
  READS the committed posture: `exempt-posture` is judged only when the gate is
  physically closed at cast time (postureFaceDown=false, phone face-up -- the reading
  this device had on 2026-09-26: proximity 5.00 far, gravity z=-9.80 screen-up) and
  is SKIPPED when the gate is open (nothing to exempt). The DND leg carries the
  deterministic exemption proof either way.
- **Process start does not read the real plug state** (observed): core `plugged`
  starts false and only POWER_CONNECTED/DISCONNECTED broadcasts change it, so every
  leg works from a deliberate transition, never from the ambient charger state.
- **End state**: the device stays on USB power, so after `battery reset` the app
  re-casts the charging screen -- the truthful state of a plugged-in phone.
