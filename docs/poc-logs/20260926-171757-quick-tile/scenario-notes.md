## scenario notes (Quick Tile Entry, ticket #54 / spec 0006)

- **Real click path**: the tile is added with `cmd statusbar add-tile` (Android 13+),
  the shade opened with `expand-settings`, the tile located by `uiautomator dump`
  bounds and clicked with `input tap` -- SysUI dispatches a genuine TileService click.
- **State seam**: tile ACTIVE/INACTIVE mirrors `Presence` (the project`s single
  on-screen fact), refreshed on onStartListening and after each click (+1.5s delayed
  sync for the async session close). No persistent polling.
- **Manual source**: the tile routes through the SAME ManualCast/ManualExit core
  events as Debug Bypass -- exempt from DND Follow and Posture Gate, never withdrawn
  by the automatic logic (the exempt leg clears the test notifications and watches
  the ExitDashboard line count stay flat).
- **Lock leg**: keyguard confirmed via KeyguardServiceDelegate before the tap; the
  cast under lock takes the E14 task-move route (rear display admittance while locked).
- **No new permissions**: TileService needs only the standard BIND_QUICK_SETTINGS_TILE
  (held by the system); guarded by TileManifestTest.
