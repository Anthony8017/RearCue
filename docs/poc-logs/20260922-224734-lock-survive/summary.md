# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-224734-lock-survive
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 1 |
| listener connected | False |
| posted / removed | 0 / 0 (allowlist hits:  / ) |
| effects | LaunchDashboard |
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

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (202,674 bytes)
- dumpsys-display.txt (340,157 bytes)
- dumpsys-window.txt (172,238 bytes)
- e13-inject-errors.txt (0 bytes)
- e13-inject-ps.txt (472 bytes)
- e13-lock-survive.txt (34,296 bytes)
- e13-power-group.txt (529 bytes)
- e13-samples.txt (2,127 bytes)
- e13-wake-ticks.txt (3,239 bytes)
- logcat-rearcue.txt (0 bytes)
- logcat-system-rear.txt (70,130 bytes)
- scenario-notes.md (2,775 bytes)
- screenshots\main.png (23,644 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (305 bytes)
- state.txt (1,840 bytes)
- transcript.txt (12,850 bytes)
