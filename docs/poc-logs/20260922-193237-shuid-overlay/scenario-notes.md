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
