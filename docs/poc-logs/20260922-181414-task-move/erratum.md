# erratum -- tooling artifacts of the archived output (ticket #18)

Raw output of this session is left exactly as generated (archive is never rewritten). The three
items below are defects of the GENERATING SCRIPT discovered after the run; they do not touch the
verdict, which stands on `dumpsys activity activities` placement + system log lines + app log.
All three are fixed in `tools/ex/09-task-move.ps1` / `tools/ex/ExCommon.psm1` after this run.

1. **`scenario-notes.md` is missing one paragraph** (the "how the transaction number is verified"
   paragraph). Cause: `'...' -f $a, $b` passed directly as a .NET method argument parses as TWO
   arguments (`Add(('...' -f $a), $b)`), so the format call threw `FormatError` and only that one
   line was dropped (the run itself continued; stderr of the job shows it). The paragraph says:
   the control group runs the EXACT command of the locked phase
   (`service call activity_task 51 i32 <rootTaskId> i32 1`) while unlocked and the fact checked is
   the task stack (`dumpsys activity activities` must show the Dashboard root task on display 1
   again); the round trip also proves the argument order `<taskId> <displayId>`; the `service call`
   reply is archived but never judged on; code 51 was found in fixture-collection round
   `docs/poc-logs/20260922-174125-task-move-collect/` (MRSS code 50 is a silent no-op on this
   Android 16 build, see its raw-14-code-scan.txt).

2. **`e14-task-move.txt` verdict line `locked-deny-lines : 10` is noise-inflated.** The deny
   classifier of that build counted unrelated chatter (`SettingsProvider: java.lang.SecurityException ... user 999`,
   `Java_Reflection: Caused by: java.lang.SecurityException: Disallowed call for uid 10224`,
   MIUI `updateSignalInfo not allowed`) into the deny bucket. The task-move-relevant refusal count
   of this run is **0** -- there is no `rearDisplay check locked` / `aborted activity` /
   `Permission Denial` line anywhere in the locked-phase evidence. The parser anchor is tightened
   after this run (context-scoped `SecurityException`/`Permission Denial`, `Not allow non-system
   app` / `not allow ... rear display`), with the noise lines of THIS run as the regression fixture
   (`tools/ex/tests/fixtures/logcat-task-move-noise.txt`).

3. **Cosmetic**: in the `# attempts` block of `e14-task-move.txt`, the `on-rear=` field of the two
   move-AWAY attempts (A2, B1) renders as `on-rear=first+s` (empty value for "never in this
   segment"). The samples themselves are correct; the formatter now prints `never-in-segment`.

Unrelated and left as-is: `raw-21/raw-22` flattening in the fixture-collection round (see its
`collection-notes.md`).
