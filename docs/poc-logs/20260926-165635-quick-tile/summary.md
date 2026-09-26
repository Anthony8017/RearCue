# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260926-165635-quick-tile
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 3599 |
| listener connected | True |
| posted / removed | 36 / 7 (allowlist hits:  / ) |
| effects | LaunchDashboard, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | True / True |
| takeover signals | miui.intent.action.SUB_SCREEN_OFF, android.intent.action.SCREEN_OFF, android.intent.action.SCREEN_ON, miui.intent.action.SUB_SCREEN_ON, android.intent.action.MAIN, android.intent.action.VIEW |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

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

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (158,957 bytes)
- dumpsys-display.txt (349,548 bytes)
- dumpsys-window.txt (173,944 bytes)
- logcat-rearcue.txt (504,492 bytes)
- logcat-system-rear.txt (118,602 bytes)
- qs-dump.xml (78,762 bytes)
- quick-tile.txt (1,141 bytes)
- quick-tile-logcat.txt (503,708 bytes)
- scenario-notes.md (1,161 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (303 bytes)
- state.txt (3,739 bytes)
- transcript.txt (17,520 bytes)
