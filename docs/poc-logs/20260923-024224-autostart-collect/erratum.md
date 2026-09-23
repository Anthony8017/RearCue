# erratum

This collection round captured NOTHING from the device: every `adb` invocation printed
adb`s own help text (170 lines). The PC-side call was broken -- the helper`s parameter was
named `$args`, PowerShell`s automatic variable, so the argument array was mangled and adb
received no real subcommand. Every raw-*.txt here is adb help output, not device data.

Verdict for this round: RUN-INVALID (tooling fault, not a device fact).
The clean rerun is the next `-autostart-collect` session. Files kept as-is (no retro-edit).
