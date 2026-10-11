# A desktop shortcut or double-click leaves a console open. Closing it sends
# SIGHUP and the bridge exits. A minimized shortcut still keeps a taskbar
# button, and ShowWindow cannot clear that button. Relaunch this launcher with
# no console, then leave: the visible cmd exits, the hidden one owns the bridge.
# -Launch is for the shortcut itself (powershell -File ... -Launch).
# Scheduled-task (parent svchost) and the retry harness (parent node) do not
# match explorer.exe, so they stay attached to their own process tree.
param([switch]$Launch)
$ErrorActionPreference = "Stop"

function Start-HiddenLauncher {
    $launcher = Join-Path $PSScriptRoot "start-bridge.cmd"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = "/d /s /c `"" + $launcher + "`""
    $psi.WorkingDirectory = $PSScriptRoot
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $started = [System.Diagnostics.Process]::Start($psi)
    if (-not $started) { exit 1 }
    exit 2
}

if ($Launch) { Start-HiddenLauncher }

$me = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
if (-not $me) { exit 0 }
$launcherProc = Get-CimInstance Win32_Process -Filter "ProcessId=$($me.ParentProcessId)"
if (-not $launcherProc -or $launcherProc.Name -ne "cmd.exe") { exit 0 }
$owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($launcherProc.ParentProcessId)"
if (-not $owner -or $owner.Name -ne "explorer.exe") { exit 0 }
Start-HiddenLauncher
