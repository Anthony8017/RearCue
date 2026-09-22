# erratum -- aborted run (ticket #16)

This session archive is NOT a round of the E12 experiment: `08-wake-keepalive.ps1` hit a
PowerShell parse error (one missing `)` on the `keyguard at start` line) and never ran, so only
the `05-collect` step produced artifacts here. No device experiment happened in this window.

Raw output is left untouched (archive rule: no retro edits). The parse error is fixed and the
first real E12 round is the session created afterwards.
