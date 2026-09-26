# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-163909-posture-gate
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 324 |
| listener connected | True |
| posted / removed | 32 / 4 (allowlist hits: com.android.shell,com.rearcue.poc / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | True / True |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

## scenario notes (Posture Gate, ticket #53 / spec 0006)

- **Production seam only**: TYPE_PROXIMITY -> `near = face-down` (values[0] < maxRange/2)
  -> PostureStableWindow 800ms both directions -> PostureGate event into the core.
  The sensor smoke (face-down reads near, face-up reads far) is the app log line
  `Posture Gate Monitor ... / posture-commit face-down near=true` vs `... near=false`.
- **The operator flips the phone**: on-change sensors are silent while still, so no adb
  path can drive posture. Every flip leg prints an OPERATOR note and waits for the
  app`s committed-posture ASCII anchor (`posture down` / `posture up`).
- **Known init gap (documented in code)**: the sensor delivers no initial reading on
  registration, so between process start and the first physical flip the core keeps its
  benign default (gate open). It can never wrongly SUPPRESS; a cast in that idle window
  self-corrects on the first committed face-up.
- **Stability leg**: the run anchors Launch/Exit line counts after preflight; two human
  flips (with natural wobble) must yield exactly 2 Launch + 2 Exit lines -- debounce
  leakage would inflate the counts. The JVM cases (PostureStableWindowTest) pin the
  window semantics separately.
- **DND stays off all run** (the other gate): asserted via mZenMode at preflight.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (160,314 bytes)
- dumpsys-display.txt (348,700 bytes)
- dumpsys-window.txt (161,934 bytes)
- logcat-rearcue.txt (46,705 bytes)
- logcat-system-rear.txt (118,224 bytes)
- posture-gate.txt (1,175 bytes)
- posture-gate-logcat.txt (46,029 bytes)
- scenario-notes.md (1,339 bytes)
- screenshots\main.png (473,093 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (305 bytes)
- state.txt (3,390 bytes)
- transcript.txt (10,609 bytes)
