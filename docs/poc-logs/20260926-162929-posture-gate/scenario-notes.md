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
