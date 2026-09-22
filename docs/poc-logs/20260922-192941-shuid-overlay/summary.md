# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-192941-shuid-overlay
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state OFF (committed OFF)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events |  |
| listener connected |  |
| posted / removed | 1 / 1 (allowlist hits:  / ) |
| effects |  |
| projection sent / confirmed |  /  |
| icon set updates |  |
| exit requested / detached |  /  |
| takeover signals |  |
| shizuku server / granted |  /  |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### shuid-overlay.txt

```
control-display0   : control-failed (probe add ok=False dumpsys found=False displayId= ownerUid=)
probe-remove       : remove-unconfirmed (probe remove ok=False window absent after remove=True)
rear-display       : rear-absent (probe add ok=False add failed=True dumpsys found=False displayId= ownerUid=)
rear-policy-deny   : no rear-window-policy line captured
system-deny-lines  : 0 (control) / 0 (rear)
register-check     : attempted=True register-result=none probe-done=False
pollution          : 1 external hit(s) -- see erratum.md
sh-uid             : SH-UID-RUN-INVALID (external pollution during the run: 1 fingerprint/power-button hit(s) this run never pressed KEYCODE_POWER -- samples void, see erratum.md)
```

## scenario notes (SH-UID overlay probe, ticket #17)

- **Equivalence boundary ("via Shizuku UserService")**: the probe process is `adb shell
  app_process ... ShuidOverlayProbe`, i.e. shell uid 2000 -- the same UID IDENTITY a
  Shizuku UserService runs with. The binder path (app -> Shizuku -> UserService) is NOT
  exercised (that would need an APK build + install, and the MIUI USB install dialog is a
  manual stop condition). What the verdict answers is "does the rear-display window
  policy admit a TYPE_APPLICATION_OVERLAY window added by a uid-2000 process".
- **Window attribution**: the window context comes from createPackageContext of the
  requested package (com.rearcue.poc), so the window carries the SAME
  package attribution as the ticket #10 E9 probe (`package=com.rearcue.poc` in dumpsys)
  and the ONLY changed variable versus E9 is the calling uid (2000 vs the app uid).
  `-WindowPackage system` switches to the bare system context (package "android"); use it
  when the package attribution itself needs to be ruled in or out.
- **`remove` mode semantics**: a window can only be removed by the process that added it
  (the WindowManager view lives in that process), so the ticket's remove step is a
  one-shot add -> remove cycle inside one probe process; the `add` phases remove their own
  window after the hold and the after-snapshots prove it left `dumpsys window windows`.
- **Build is part of the command**: javac (JDK17 under %LOCALAPPDATA%\RearCue-tools) +
  d8 (build-tools) run every invocation, output archived in shuid-build.txt; a build
  failure stops the run with SH-UID-RUN-INVALID (no device verdict is claimed).
- **Screen handling**: this run NEVER presses KEYCODE_POWER (the phone stays unlocked);
  `stay_on_while_plugged_in` is set to 7 for the run and restored to its original value
  after it (shuid-screen-settings.txt records both values).
- **Why window adds from this process cannot be admitted on this build** (device facts,
  see the artifacts): every WindowManager.addView from the uid-2000 app_process probe
  is refused with `Unknown pid=<pid> uid=2000` (system side: `WindowManager: Window
  Manager Crash java.lang.IllegalStateException: Unknown pid=...` and
  `attachWindowContextToDisplayArea: calling from non-existing process pid=... uid=2000`),
  on display 0 and on the rear display alike. The app attach handshake that would
  register the process (ActivityThread.attach(false)) gets the probe KILLED instead --
  the register-check phase documents exactly that and is the LAST phase for that reason.
**Observation window is short by design** (-HoldSeconds 6s per add phase, -SettleSeconds 2s).

## artifacts
- dumpsys-activities.txt (199,021 bytes)
- dumpsys-display.txt (340,099 bytes)
- dumpsys-window.txt (136,833 bytes)
- dumpsys-window-control-after.txt (136,833 bytes)
- dumpsys-window-control-held.txt (136,833 bytes)
- dumpsys-window-rear-after.txt (136,833 bytes)
- dumpsys-window-rear-held.txt (136,833 bytes)
- dumpsys-window-registercheck-after.txt (136,833 bytes)
- dumpsys-window-registercheck-held.txt (136,833 bytes)
- dumpsys-window-remove-after.txt (136,833 bytes)
- dumpsys-window-remove-held.txt (136,833 bytes)
- erratum.md (368 bytes)
- logcat-rearcue.txt (0 bytes)
- logcat-shuid-control-all.txt (103,723 bytes)
- logcat-shuid-control-system.txt (858 bytes)
- logcat-shuid-rear-all.txt (126,762 bytes)
- logcat-shuid-rear-system.txt (728 bytes)
- logcat-shuid-registercheck-all.txt (235,490 bytes)
- logcat-shuid-registercheck-system.txt (728 bytes)
- logcat-shuid-remove-all.txt (133,530 bytes)
- logcat-shuid-remove-system.txt (728 bytes)
- logcat-system-rear.txt (480 bytes)
- scenario-notes.md (2,694 bytes)
- screenshots\main.png (164,720 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (306 bytes)
- shuid-build.txt (925 bytes)
- shuid-overlay.txt (7,290 bytes)
- shuid-probe-control.txt (804 bytes)
- shuid-probe-rear.txt (804 bytes)
- shuid-probe-registercheck.txt (806 bytes)
- shuid-probe-remove.txt (810 bytes)
- shuid-screen-settings.txt (365 bytes)
- state.txt (1,591 bytes)
- transcript.txt (7,880 bytes)
