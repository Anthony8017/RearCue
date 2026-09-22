# collection notes -- E14 fixture-collection round (ticket #18)

This round is the fixture-collection round for the E14 task-move probe (the ticket #16
`20260922-155740-wake-collect` precedent): it runs the task-move transaction by hand while the
phone is UNLOCKED, purely to capture real device output for the parser fixtures and to find the
transaction code before the one-shot locked run. It is not a verdict round.

## What was run (in order, all via `adb -s 94250f9e`)

1. `service check activity_task` -> `Service activity_task: found` (raw-00-service-check.txt)
2. `dumpsys activity activities` while a Dashboard was on the rear (raw-01-dashboard-on-rear.txt;
   at that moment the owner-side state had changed and the rear was back to SubScreenLauncher --
   kept as-is as a real "no dashboard task" sample)
3. prep: `Start-ExApp` + `CANCEL_TEST`/`CANCEL_PACKAGE` + `cmd notification post -t 'RearCue ex'
   rearcue-ex14 ...` -> Dashboard root task `t12984` on display 1 (raw-10-prep-dashboard-on-rear.txt)
4. `service call activity_task 50 i32 12984 i32 0` (the MRSS-recorded code) -> reply
   `Result: Parcel( 00000000    '....')`, `dumpsys activity activities` still shows the task on
   display 1 (raw-11-*): the recorded code 50 is a SILENT NO-OP on this Android 16 build
5. reply-parcel comparison (hint only, never a verdict): `service call activity_task 999 ...` ->
   `Result: Parcel(Error: 0xffffffffffffffb6 "Not a data message")` vs code 50 -> void success
   (raw-13-reply-comparison.txt) => code 50 exists but is not the task move
6. ground truth for the move path: `cmd activity display move-stack 12984 0` / `... 1` really
   moves the root task both ways (raw-12-cmd-activity-help.txt documents the shell command)
7. transaction-code scan (raw-14-code-scan.txt): `service call activity_task N i32 <taskId> i32 1`
   candidates outward from 50 against a DISPOSABLE task (our debug MainActivity task), observable =
   `dumpsys activity activities` placement, reset between probes via `cmd activity display
   move-stack` -> **N=51 moved the task (0 -> 1)**; N=50 did not. So on this build the MRSS/REAREye
   constant 50 is off by one and the E14 probe runs with **code 51**
8. code 51 verified both directions on the disposable task (raw-15-code51-both-directions.txt) and
   then on the real Dashboard task (raw-20/21/22): `service call activity_task 51 i32 12985 i32 0`
   moved it to display 0, `... i32 1` moved it back onto the rear display

## Fixtures extracted from this round (verbatim, characters unchanged)

- `tools/ex/tests/fixtures/dumpsys-activities-task-moved.txt` <- raw-21-after-move-to-0.txt
- `tools/ex/tests/fixtures/service-call-task-move.txt` <- the reply lines of raw-21 / raw-13

Two other fixtures come from earlier archives (also verbatim slices):
`logcat-task-move-locked-deny.txt` (session 20260922-162618-wake-keepalive) and
`logcat-task-move-reap.txt` (session 20260922-125937-overlay-lock).

## Capture flaws in THIS round (stated, not edited)

- `raw-21-service-call-move-to-0.txt` / `raw-22-service-call-move-to-rear.txt`: the save call hit
  the PowerShell comma-precedence trap (`'a' + $x + 'b', $y` -- the comma binds tighter than `+`,
  ticket #16 listed the same trap), so the comment line and the reply were flattened into one
  line. The reply substring itself is intact and was extracted verbatim for the fixture; the files
  are left as captured.
- `date '+%m-%d %H:%M:%S'` was once passed as two argv entries (PowerShell ate the quotes) and
  printed `date: Max 1 argument`; 08/09 pass it as one string.

## Mechanism fact learned here (used by 09-task-move.ps1)

A task move destroy+recreates the moved activity on the display configuration change -- the app
logs `Dashboard detach 实例数=0` followed by `RearDashboardActivity onCreate display=<new>`. The
TASK id stays stable across the move, so placement is read by task id, never by instance count.
