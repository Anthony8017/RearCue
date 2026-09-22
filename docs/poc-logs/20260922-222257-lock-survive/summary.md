# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-222257-lock-survive
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 3 |
| listener connected | False |
| posted / removed | 0 / 0 (allowlist hits:  / ) |
| effects |  |
| projection sent / confirmed | False / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals |  |
| shizuku server / granted |  /  |
| crashes (FATAL/ANR) | 0 |

## step verdicts

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

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (207,272 bytes)
- dumpsys-display.txt (340,591 bytes)
- dumpsys-window.txt (177,474 bytes)
- e13-inject-errors.txt (0 bytes)
- e13-inject-ps.txt (472 bytes)
- e13-lock-survive.txt (23,335 bytes)
- e13-power-group.txt (0 bytes)
- e13-samples.txt (2,054 bytes)
- e13-wake-ticks.txt (3,263 bytes)
- erratum.md (299 bytes)
- logcat-rearcue.txt (217 bytes)
- logcat-system-rear.txt (115,252 bytes)
- scenario-notes.md (2,224 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (305 bytes)
- state.txt (1,487 bytes)
- transcript.txt (15,685 bytes)
