# Issue #37 device verdict

result: RED
reason: three legs completed; see per-leg verdicts
serial: 94250f9e
run-id: e1f6d535d9
budget: 5s from pre-post device log marker
mode: full chain
system receipt is bounded by the pre-post marker and first observed notification key; only notification keys are archived

firstcast-off: RED-NO-CALLBACK (system accepted but listener callback missing within budget); callback=ms icon=ms owner=ms
update-off: GREEN (system, callback, icon set and dashboard within budget); callback=295ms icon=295ms owner=576ms
control-on: GREEN (system, callback, icon set and dashboard within budget); callback=318ms icon=318ms owner=4218ms

See timeline.txt for device-clock evidence and cleanup.
