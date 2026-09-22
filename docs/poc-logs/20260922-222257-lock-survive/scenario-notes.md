## scenario notes (E13 lock survival, ticket #19)

- **Protocol**: Activity baseline up (one shell notification -> Dashboard on the rear) ->
  the E12 injection loop started BEFORE the lock and kept running across it (MRSS-style:
  keep-alive means the wake keys are already flowing when the display group powers off) ->
- **Injection interval**: `500ms`, a command parameter (`-WakeIntervalMs`), converted to a `sleep` argument of 0.5s.
- **Two clock anchors on the lock**: `pc-e13-lock-issued` into the RearCue tag is the
  survive-parser anchor (same device clock as the app`s `Dashboard detach` line, so
  "reclaimed at +Xs" carries no PC skew); the `date` stamp next to the KEYCODE_POWER press
  is the authoritative T0 for tick/pollution attribution, because the main log buffer wraps
  under injection load (08-wake-keepalive lesson).
- **Log buffer**: the run grows the main log buffer to 32M first and restores it at the end
  (main log buffer size unreadable; left as-is), so the lock marker survives the injection flood.
- **Keep-alive is the E12 loop verbatim** (`/data/local/tmp/wake-keepalive.sh`, shell uid
  2000): E13 is about the Dashboard`s survival GIVEN the keep-alive, so the treatment is
  deliberately the one already validated by E12 -- no new mechanism is mixed into this probe.
  The deviation ticket #16 recorded still applies: the loop runs in an adb shell (same uid as
  a Shizuku UserService), not through the binder path.
- **The control leg is archived, not repeated**: without keep-alive the Dashboard is reclaimed
  1.3-1.4s after the lock (ticket #11 runs, pinned as the logcat-survive-cleared-* fixtures).
  This run spends its whole lock on the keep-alive question instead of re-proving the control.
- **Deviation -- no inter-segment unlock**: a secure keyguard cannot be dismissed over adb
  (ticket #7), so the run resets to "wake + dismiss attempt + settle + require rear=ON" and
  ends with the phone locked (the restore is best effort).
- **Pollution policy**: fingerprint wakes and power-button transitions that are not one of
  this run`s own device-clock-stamped presses void the round (E13-RUN-INVALID + erratum.md),
  per the ticket #11/#16 lesson.
