## scenario notes (E13 lock survival, ticket #19)

- **Protocol**: Activity baseline up (one shell notification -> Dashboard on the rear) ->
  the APP`s own Wake Keep-alive (started with the projection) kept running across the lock
  (one KEYCODE_POWER lock -> 60s survival watch, sample every 2s). The exit at the end
  doubles as the ticket #21 lifecycle proof (`e13-exit-residual.txt`).
- **Precondition**: the debug APK is already installed (`-Task install` / `-Task drive` do
  that; this task deliberately does not reinstall), the listener grant is re-asserted by
  the 02-authorize step, and the phone starts unlocked (a swipe-up dismisses this keyguard,
  `wm dismiss-keyguard` is a silent no-op -- ticket #7).
- **Injection interval**: `500ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 0.5s.
- **Two clock anchors on the lock**: `pc-e13-lock-issued` into the RearCue tag is the
  survive-parser anchor (same device clock as the app`s `Dashboard detach` line, so
  "reclaimed at +Xs" carries no PC skew); the `date` stamp next to every injected input (lock
  presses and wake pokes alike) is the authoritative T0 for tick/pollution attribution,
  because the main log buffer wraps under injection load (08-wake-keepalive lesson).
- **Log buffer**: the run grows the main + system log buffers to 32M first (main 2048Kb / system 2048Kb grown to 32M for the run (restored at the end)) so the lock
  marker and the baseline window survive the injection flood. The shrink back happens in
  `ex.ps1` AFTER the 05-collect step: `logcat -G` truncates the ring buffer, and shrinking
  first once left the collect capture with 1 event and wrong chain facts (20260922-224734).
- **Keep-alive is the APP`s own Wake Keep-alive** (`WakeKeepAlive.kt` through the Shizuku
  UserService, `input -d 1 keyevent KEYCODE_WAKEUP` every `-WakeIntervalMs`): the ticket #21
  regression treats with the SHIPPED implementation, not the probe loop -- `wake-keepalive.sh`
  is never started in this mode and `e13-wake-ticks.txt` is empty BY DESIGN. Tick evidence is
  the app`s own `wake-keep-alive start|ok|stop|fail` markers (Get-ExAppKeepAliveFacts), with
  the system-side `WAKE_REASON_WAKE_KEY` traces and the per-2s rear/owner samples as the
  independent cross-checks.
- **The control leg is archived, not repeated**: without keep-alive the Dashboard is reclaimed
  1.3-1.4s after the lock (ticket #11 runs, pinned as the logcat-survive-cleared-* fixtures).
  This run spends its whole lock on the keep-alive question instead of re-proving the control.
- **Deviation -- no secure unlock needed on this build**: `wm dismiss-keyguard` is a no-op on
  this keyguard (ticket #7), but a swipe up dismisses it (verified 2026-09-22); the run still
  starts from an unlocked phone and its restore is wake + dismiss attempt + swipe, best effort.
- **Pollution policy**: fingerprint wakes and power-button transitions that are not one of
  this run`s own device-clock-stamped injections void the round (E13-RUN-INVALID + erratum.md),
  per the ticket #11/#16 lesson. Two signatures are absolved as our own: a transition within
  +-3s of a stamped injection, and a `power_button` power-off within 200ms after a
  WAKE_REASON_WAKE_KEY event (the wake key`s own mode flip -- real round
  20260922-222257-lock-survive had one 3ms behind a rear-display wake, fixture
  logcat-e13-wake-flip.txt).
