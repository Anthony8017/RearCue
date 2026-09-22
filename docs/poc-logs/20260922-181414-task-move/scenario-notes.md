## scenario notes (E14 task-move probe, ticket #18)

- **Why the order is prep -> control -> position -> lock -> locked transaction**: this
  device has a SECURE keyguard (PIN/fingerprint) that adb cannot dismiss (`wm
  dismiss-keyguard` no-ops; tickets #7/#16), and KEYCODE_POWER leaves the phone locked
  until the owner unlocks it. The run is therefore ONE SHOT: every segment that needs the
  unlocked state runs before the lock, and a failed prep/control stops the run BEFORE
  KEYCODE_POWER is ever pressed (the phone is left usable).
- **How the transaction number is verified ("how do you know the code is right")**: the
  control group runs the EXACT command of the locked phase (`service call activity_task
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
