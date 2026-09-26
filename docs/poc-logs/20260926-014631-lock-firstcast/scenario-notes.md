## scenario notes (lock-screen first cast, ticket #22)

- **Clean slate first**: both test notifications are cancelled and any Dashboard is
  confirmed gone BEFORE the lock -- this is a FIRST cast, not a re-projection.
- **The route is the E14 transaction**: `service call activity_task 51 i32 <taskId>
  i32 1` (moveRootTaskToDisplay; code 51 is the one E14 verified -- MRSS`s 50 is a
  silent no-op on this build). The `am start --display` path is refused while locked
  (`rearDisplay check locked -> deny`) and is deliberately skipped by the app.
- **The trigger is the real-world one**: one allowlist notification while locked (the
  E1 drive method: shell notification + debug POST_TEST -- both are allowlist apps).
- **Judged from device facts only**: owner=dashboard per 2s sample + the app`s ASCII
  `task-move word=...` markers (Get-ExTaskMoveAppFacts) + system refusal lines; the
  `service call` return is archived (`task-move txn-raw`) but never judged (E14 rule).
- **The exit half**: clearing the notifications must hand the rear back to the native
  interface (`firstcast-exit : native-returned`); the app also moves a task-move-moved
  root task back to display 0 (`task-move hand-back` marker).
- **Icon rendering**: the device facts prove the Dashboard (with the Icon Set content
  logged by `ACTION_STATE`) is on the rear; the pixels themselves are the app`s Compose
  UI -- for human-grade proof take a photo at the peak per the photo-checkpoints practice.
- **Deviation**: the main display state is not sampled (the question is the rear chain);
  the wire keeps the slot as `main=not-sampled`. The app process is alive but has NO
  foreground activity -- the real "phone idle" condition (BAL applies).
