## scenario notes (E12 wake keep-alive, ticket #16)

- **How the injection loop starts/stops (the recorded trade-off)**: a device-side sh loop
  (`/data/local/tmp/wake-keepalive.sh`, pushed from `tools/ex/device/`) started with
  `adb shell "nohup sh ... >/dev/null 2>&1 &"` and stopped by creating a stop file the loop
  checks each iteration. Chosen over an app-side Shizuku UserService because E12 is a pure
  display-power question -- no app, no binder, no UserService lifecycle is needed, and the
  loop runs as shell uid 2000, the identity a Shizuku UserService would have anyway. The PC
  only samples; every injection happens on the device and leaves its own tick line with the
  `input` exit code, plus a `ps` snapshot while it runs.
- **Injection interval**: `5000ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 5s.
- **Legs**: baseline (control, no keep-alive) first, then keep-alive, 60s each, sample every 2s; the lock is KEYCODE_POWER (07-lock-compare precedent) and the loop runs continuously ACROSS the lock (MRSS-style: keep-alive means the wake keys are already flowing when the display group powers off).
- **Deviation -- inter-leg reset**: the ticket asks for "unlock back to steady state" between
  the legs. This device has a SECURE keyguard (PIN/biometric) that adb cannot dismiss
  (`wm dismiss-keyguard` no-ops; ticket #7 recorded the same), so the reset degrades to
  "wake + dismiss attempt + settle + require rear=ON at T0", applied IDENTICALLY to both legs.
  Both legs therefore start from the same state (keyguard up, rear ON) and remain a valid
  same-round control comparison; the absolute baseline may differ from a run made from the
  unlocked steady state.
- **Environment fact found while building**: with the proximity sensor covered (phone lying
  face down), MIUI vetoes a plain `KEYCODE_WAKEUP` (`BaseMiuiPhoneWindowManager: Going to
  sleep due to KEYCODE_WAKEUP/KEYCODE_DPAD_CENTER: proximity sensor too close`). The reset
  falls back to a marked `KEYCODE_POWER` toggle wake, and every press is stamped with the
  DEVICE clock straight from `date` so a human press stays separable from ours.
- **Why T0 is not a logcat marker**: the main log buffer wraps under injection load (2 keys/s
  for 60s flood it past the run start -- one round lost its baseline marker that way and
  misjudged the control leg). The authoritative stamps are the `date` reads next to each
  KEYCODE_POWER press and the tick log; `pc-e12-power-*` logcat lines remain as corroboration.
- **No install step**: E12 judges display power state only, so `-Task wake-keepalive` does not
  run `01-install` (no MIUI USB install dialog risk, no app dependency); `05-collect` simply
  finds no app state and records that.
