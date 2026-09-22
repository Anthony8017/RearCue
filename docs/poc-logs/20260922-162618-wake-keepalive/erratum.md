# erratum -- external pollution + one misjudged fact (ticket #16)

Raw output in this directory is left untouched (archive rule: no retro edits). Two things make
this round unusable as the E12 verdict round:

## 1. The keep-alive window was disturbed by a human (device facts)

Inside the keep-alive watch window (T0 = `09-22 16:27:47` device clock) the phone was touched
from outside the experiment -- `e12-power-group.txt` verbatim:

```
09-22 16:27:48.999  5157 10382 I PowerGroup: Waking up power group from Dozing (groupId=0, uid=10224, reason=WAKE_REASON_UNKNOWN, details=android.policy:FINGERPRINT:UnlockFinishT)...
09-22 16:27:51.627  5157 10059 I PowerGroup: Waking up power group from Dozing (groupId=0, uid=10224, reason=WAKE_REASON_UNKNOWN, details=android.policy:FINGERPRINT:UnlockFinishT)...
09-22 16:28:04.494  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 0, uid= 1000, millisSinceLastUserActivity=12538, lastUserActivityEvent=touch)...
```

Two fingerprint wakes (one of them an unlock) and one hand-pressed power button (note
`lastUserActivityEvent=touch`) -- none of them is a script press (the script's device-clock
press stamps are `16:27:35` reset, `16:27:47` lock, `16:27:49` lock2). The consequences are
visible in the samples: `main=ON/ON` for most of the window and `owner=dashboard` (the phone got
unlocked and the app re-projected). "Rear stayed ON" in this window is therefore NOT a wake-key
fact and the `E12-*` verdict of this run must not be quoted.

## 2. The baseline T0 was misjudged for a tooling reason

`baseline-lock-poweroff: False` is a tooling artifact, not a device fact: the `pc-e12-power-*`
logcat marker was pushed out of the MAIN log buffer by the injection load (buffer wrap -- the
captured app log starts at `16:27:35`, everything earlier is gone), so the parser had no T0 to
match against. The baseline lock DID reach the rear display group, verbatim:

```
09-22 16:26:28.694  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, ...)
```

and the control leg itself is sound (`rear left ON at +6s ... ending DOZE_SUSPEND/DOZE_SUSPEND`).

Both issues are fixed in the script afterwards (T0/press stamps read from the device `date`
instead of logcat; pollution anchored on `android.policy:FINGERPRINT` + power_button transitions
instead of the single `finishCallBack` variant). Findings must use the clean rerun that follows
this session.
