# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-014122-task-move
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 94 |
| listener connected | True |
| posted / removed | 17 / 0 (allowlist hits: com.android.shell / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | False / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e14-task-move.txt

```
control-off-rear      : True (C0 moved the task to display 0 while unlocked)
control-on-rear       : True (C1 = the very command of the locked phase, unlocked)
pre-lock-placement    : t13377@d0
locked-deny-lines     : 0
task-on-rear-samples  : first=+9s last=+55s
task-end-placement    : d1
rear-end-state        : ON/ON
phase-b               : rear-awake variant landed too (B2 at +49s)
pollution             : none detected
e14                   : E14-PASS (locked steady state: the task-move transaction landed the Dashboard root task t13377 on display 1 -- first seen at +9s of the A1-locked-to-rear watch, rear state ON/ON; before that attempt the task was t13377@d0)
```

## scenario notes (E14 task-move probe, ticket #18)

- **Why the order is prep -> control -> position -> lock -> locked transaction**: this
  device has a SECURE keyguard (PIN/fingerprint) that adb cannot dismiss (`wm
  dismiss-keyguard` no-ops; tickets #7/#16), and KEYCODE_POWER leaves the phone locked
  until the owner unlocks it. The run is therefore ONE SHOT: every segment that needs the
  unlocked state runs before the lock, and a failed prep/control stops the run BEFORE
  KEYCODE_POWER is ever pressed (the phone is left usable).
- **How the transaction number is verified ("how do you know the code is right")**: the
  control group runs the EXACT command of the locked phase (`service call activity_task
  51 i32 <rootTaskId> i32 1`) while unlocked and the fact checked is the task stack:`dumpsys activity activities` must show the Dashboard root task on display 1 again. The round trip
  (off the rear and back) also proves the argument order `<taskId> <displayId>`. The
  `service call` reply is archived but never judged on. Fixture-collection round
  `docs/poc-logs/20260922-174125-task-move-collect/` records how code 51 was found (MRSS`s
  code 50 is a silent no-op on this Android 16 build; raw-14-code-scan.txt).
- **Observation window and sampling interval are parameters** (`-ObserveSeconds` 6s per
  attempt, `-SampleSeconds` 1s, `-SettleSeconds` 6s); `-TxnCode` overrides the transaction
  number and `-NoRearWake` drops the rear-awake variant.
- **Phase B (rear-awake variant)**: after the plain attempts, the rear display is woken
  with one display-directed `input -d <rear> keyevent KEYCODE_WAKEUP` (E12 mechanism) and
  the same transaction is repeated. It separates "the target display was powered off" from
  "the lock gate refused the move"; both variants are reported separately.
- **Code-scan safety deviation (documented)**: when the given `-TxnCode` does not move
  anything, the scan probes `service call activity_task N i32 <taskId> i32 <displayId>`
  candidates against a DISPOSABLE task of our own package (debug MainActivity task), not the
  Dashboard task, so a destructive transaction in the range cannot eat it. The candidate
  code is judged the same way as everything else: `dumpsys` placement, not the reply.
- **A task move destroy+recreates the moved activity** (display configuration change: the
  app logs `Dashboard detach` then `RearDashboardActivity onCreate display=N`). The task id
  stays stable across the move, so placement is read by task id, never by instance count.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (73,671 bytes)
- dumpsys-display.txt (349,746 bytes)
- dumpsys-window.txt (122,007 bytes)
- e14-samples.txt (7,804 bytes)
- e14-service-calls.txt (1,305 bytes)
- e14-task-move.txt (62,881 bytes)
- logcat-rearcue.txt (12,410 bytes)
- logcat-system-rear.txt (108,730 bytes)
- scenario-notes.md (2,559 bytes)
- screenshots\main.png (12,921 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (302 bytes)
- state.txt (2,129 bytes)
- transcript.txt (21,458 bytes)
