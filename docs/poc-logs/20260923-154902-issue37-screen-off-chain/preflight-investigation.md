# Issue #37 preflight investigation (2026-09-23)

The one-command entry point returned `BLOCKED` before any device mutation. The device-clock
timeline in `timeline.txt` shows the same unlocked Main Display ON, rear owner Dashboard and
10 active allowlist notifications before and after. A later read-only check, while holding
`Global\RearCueDevice`, found `KeyguardServiceDelegate showing=false secure=true`, and
`dumpsys lock_settings` reported `User 0` with `CredentialType: PIN`. `cmd notification list`
reported 10 allowlist keys from `com.ss.android.lark` (keys only; no notification bodies).

The existing #35 acceptance in `../20260923-134033-issue35-device-acceptance/` already proves
an adb power key can establish this device's secure keyguard: `corrected-lock-samples.txt`
starts immediately after a power-key injection while keyguard was hidden and then samples
`keyguard=True`; `instrumented-listener-screen-off.txt` records a later screen wake with
keyguard still showing. Its final state was locked, Main Display OFF. Waking a PIN-locked phone
does not restore an initially unlocked state. We therefore did not repeat a sleep/wake mutation
from the current unlocked start, because that would fail the device-state restoration contract.

For an exact empty first-cast run, the device must be handed to the harness already locked,
with no active Allowlist App notifications and Native Rear Screen owning the rear. Current
Feishu notifications must expire or be cleared by the owner; this harness never cancels them.
