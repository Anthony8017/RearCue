# erratum -- run 4 stable-leg misassertion (ticket #53 posture-gate)

The in-script `stable` leg asserted "exactly 2 LaunchDashboard + 2 ExitDashboard lines",
assuming the operator flips exactly twice. The operator made 4 committed flips (each held
>800ms -- real flips, not wobble), so the arithmetic read 3+3 and the leg printed FAIL
(`posture-gate.txt` stands as archived).

The device behavior was correct throughout: every effect line pairs 1:1 with a committed
cause and the 800ms window absorbed all intra-flip wobble. `posture-stable-rejudge.txt`
re-judges the SAME archived logcat with the corrected assertion (orphan-effect check:
any Launch/Exit line no cause accounts for = oscillation): **0 orphans = PASS**.

The script (tools/ex/19-posture-gate.ps1) was fixed to the orphan-check form afterwards;
this session's evidence predates the fix and is re-judged offline, per the E-series rule
that verdicts come from archived device facts, never from command exit codes.

Also noted this session: the default logcat main buffer rotated mid-run before
`logcat -G 4096K` was added to the script (run 3 measured an Exit delta of -1); the
enlargement is now part of the script.
