# 02-authorize.ps1 -- grant notification-listener access (the system-side switch the app needs).
#
# Run through the one-command entry point (`ex.ps1`), or alone:
#   .\tools\ex\02-authorize.ps1
#
# `cmd notification allow_listener` is the only way to grant this without touching the phone.
# It has to be re-applied after every reinstall (01-install.ps1). MIUI's autostart switch
# (`AutoStartManagerService`) has no adb equivalent on this build -- that one stays a manual
# step and is reported here as a gap, not silently assumed.

[CmdletBinding()]
param([string] $Serial)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'authorize' -Serial $Serial | Out-Null }

Invoke-Adb -Arguments @('shell', 'cmd', 'notification', 'allow_listener', $config.ListenerComponent) -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'pm', 'grant', $config.Package, 'android.permission.POST_NOTIFICATIONS') -AllowFailure | Out-Null
Start-Sleep -Milliseconds 500

$enabled = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners') -AllowFailure
$granted = Test-ExListenerEnabled

$lines = @(
    '# 02-authorize',
    ('listener component : {0}' -f $config.ListenerComponent),
    ('enabled_listeners  : {0}' -f (($enabled -join '').Trim())),
    ('listener granted   : {0}' -f $granted),
    '',
    '# known gap (manual step): MIUI autostart',
    '#   `cmd appops set com.rearcue.poc AUTO_START allow` does not work on this HyperOS build',
    '#   ("Unknown operation string"), so after the process is killed by the system the listener',
    '#   rebind is rejected (AutoStartManagerService: MIUILOG- Reject service) until the user',
    '#   enables Settings > Apps > RearCue > Autostart on the phone.',
    '#   E8 evidence: docs/poc-logs/ticket6-e8-shizuku-evidence.txt, ticket #5 findings.'
)
Write-ExArtifact -Name '02-authorize.txt' -Lines $lines | Out-Null

if (-not $granted) { throw 'notification listener still not enabled -- check `adb shell cmd notification allow_listener`' }
Write-ExNote 'notification listener enabled'
return $true
