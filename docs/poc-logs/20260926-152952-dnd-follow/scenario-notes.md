## scenario notes (DND Follow, ticket #52 / spec 0006)

- **Public surface only**: DND is toggled with `cmd notification set_dnd priority|all`;
  the app hears it through the NLS interruption-filter callback -- the exact production
  seam the spec mandates (no new permission, no polling).
- **Judged from device facts**: `mZenMode` from dumpsys notification (the toggle really
  took), rear owner from `dumpsys activity activities` (dashboard / native), and the app`s
  ASCII effect lines (`dnd on`, `dnd off`, `LaunchDashboard`, `ExitDashboard`,
  `manual-cast`, `manual-exit`) -- the same anchor vocabulary as the E-series.
- **Suppress leg ordering**: DND goes on FIRST, then the notification posts; the pass
  condition is "no LaunchDashboard line after the dnd-on anchor AND owner never
  dashboard" (the notification itself is visible to the listener -- DND does not block
  posting, only the cast decision is gated).
- **Withdraw = hand-back**: the exit runs the same task-move hand-back as the
  notification-clear exit (owner must leave `dashboard`).
- **Manual exempt**: Debug Bypass PROJECT_REAR under DND (manual source tag in core),
  then EVERY notification is cancelled -- the dashboard must stay (no auto exit);
  EXIT_REAR (manual exit) is the only thing that takes it down.
- **Source-tag proof on device**: the JVM cases (DashboardCoreTest, 77 green) pin the
  auto/manual matrix; this run pins the wired behavior end to end.
