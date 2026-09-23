# Issue #37 device verdict

result: RED-OFF
reason: OFF-only firstcast completed; update and locked-on control not evaluated
serial: 94250f9e
run-id: 4a0a34f94a
budget: 5s from pre-post device log marker
mode: OFF-only firstcast
system receipt is bounded by the pre-post marker and first observed notification key; only notification keys are archived

firstcast-off: RED-NO-CALLBACK (system accepted but listener callback missing within budget); callback=ms icon=ms owner=ms
update-off: SKIPPED (app baseline needs main ON)
control-on: SKIPPED (-OffOnly)

See timeline.txt for device-clock evidence and cleanup.
