# Issue #37 device verdict

result: INVALID
reason: RearCue MainActivity occupied Rear Display after cleanup; harness hand-back is not product success
serial: 94250f9e
run-id: b280b26bae
budget: 5s from pre-post device log marker
mode: full chain
system receipt is bounded by the pre-post marker and first observed notification key; only notification keys are archived

firstcast-off: RED-NO-CALLBACK (system accepted but listener callback missing within budget); callback=ms icon=ms owner=ms
firstcast-exit: RED-OWNER (native rear not restored)

See timeline.txt for device-clock evidence and cleanup.
