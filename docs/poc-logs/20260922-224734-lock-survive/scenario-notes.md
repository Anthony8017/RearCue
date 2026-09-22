## scenario notes (E13 lock survival, ticket #19)

- **Protocol**: Activity baseline up (one shell notification -> Dashboard on the rear) ->
  the E12 injection loop started BEFORE the lock and kept running across it (MRSS-style:
  keep-alive means the wake keys are already flowing when the display group powers off) ->
  one KEYCODE_POWER lock -> 60s survival watch, sample every 2s -> stop file.
- **Injection interval**: `500ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 0.5s.
- **Two clock anchors on the lock**: `pc-e13-lock-issued` into the RearCue tag is the
  survive-parser anchor (same device clock as the app`s `Dashboard detach` line, so
  "reclaimed at +Xs" carries no PC skew); the `date` stamp next to every injected input (lock
  presses and wake pokes alike) is the authoritative T0 for tick/pollution attribution,
  because the main log buffer wraps under injection load (08-wake-keepalive lesson).
- **Log buffer**: the run grows the main + system log buffers to 32M first and restores them
  at the end (main 2048Kb / system 2048Kb grown to 32M for the run (restored at the end)), so the lock marker and the baseline window survive the injection flood.
- **Keep-alive is the E12 loop verbatim** (`/data/local/tmp/wake-keepalive.sh`, shell uid
  2000): E13 is about the Dashboard`s survival GIVEN the keep-alive, so the treatment is
  deliberately the one already validated by E12 -- no new mechanism is mixed into this probe.
  The deviation ticket #16 recorded still applies: the loop runs in an adb shell (same uid as
  a Shizuku UserService), not through the binder path.
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
