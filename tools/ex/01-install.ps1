# 01-install.ps1 -- build (optional) + install the debug APK, grant POST_NOTIFICATIONS.
#
# Run through the one-command entry point (`ex.ps1`), or alone:
#   .\tools\ex\01-install.ps1 -Build
#
# Why the extra steps (all learned the hard way, see docs/poc-findings.md):
#   * `wm dismiss-keyguard` first: installing while the keyguard is up fails with
#     INSTALL_FAILED_USER_RESTRICTED on this HyperOS build.
#   * reinstalling over a package signed with a different debug keystore fails with
#     INSTALL_FAILED_UPDATE_INCOMPATIBLE, so a plain `adb install -r` gets one retry after uninstall.
#   * a reinstall resets the notification-listener grant, so 02-authorize.ps1 must follow.

[CmdletBinding()]
param(
    [switch] $Build,
    [switch] $KeepKeyguard,
    [switch] $NoDialog,
    [switch] $NoMiuiGrant,
    [string] $Serial
)

$common = Join-Path $PSScriptRoot 'ExCommon.psm1'
if (-not (Get-Module -Name 'ExCommon')) { Import-Module $common }

$config = Get-ExConfig
if (-not (Get-ExSessionDir)) { New-ExDeviceSession -Name 'install' -Serial $Serial | Out-Null }

$apkRelative = $config.Apk
$apk = Get-ExPath $apkRelative

if ($Build -or -not (Test-Path -LiteralPath $apk)) {
    $jdk = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'RearCue-tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    Write-ExNote ('building {0} (JAVA_HOME={1})' -f $apkRelative, $env:JAVA_HOME)
    $gradle = Join-Path (Get-ExRepoRoot) 'gradlew.bat'
    $build = @(& $gradle ':app:assembleDebug' 2>&1 | ForEach-Object { [string]$_ })
    $build | ForEach-Object { Write-ExNote ('  gradle| ' + $_) }
    if (-not (Test-Path -LiteralPath $apk)) { throw ('build did not produce {0}' -f $apk) }
    Write-ExArtifact -Name '01-install.txt' -Lines $build | Out-Null
}
$apkInfo = Get-Item -LiteralPath $apk
Write-ExNote ('apk {0} ({1:N0} bytes, {2})' -f $apkRelative, $apkInfo.Length, $apkInfo.LastWriteTime)

if (-not $KeepKeyguard) { Set-ExScreenAwake }

$result = Invoke-ExInstallApk -Apk $apk -NoDialog:$NoDialog
if (-not $result.Success -and $result.Output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE') {
    Write-ExNote 'signature/version mismatch: uninstalling first (listener grant is reset, 02 re-applies it)'
    Invoke-Adb -Arguments @('uninstall', $config.Package) -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    $result = Invoke-ExInstallApk -Apk $apk -NoDialog:$NoDialog
}
if (-not $result.Success) {
    throw ('adb install failed: {0}' -f $result.Output)
}
Write-ExNote ('install ok (MIUI USB dialog confirmed={0})' -f $result.DialogConfirmed)

Invoke-Adb -Arguments @('shell', 'pm', 'grant', $config.Package, 'android.permission.POST_NOTIFICATIONS') -AllowFailure | Out-Null

# MIUI keeps `showWhenLocked` behind a per-app op (numeric MIUIOP 10020, "show on lock screen").
# A reinstall resets it, and without it the Dashboard is finished the moment the main screen locks
# -- the E3 run of 2026-09-22 11:18 shows `ActivityRecordImpl: MIUILOG- Show when locked
# PermissionDenied pkg : com.rearcue.poc` immediately followed by `Dashboard detach`. Same trap as
# the notification-listener grant, so re-apply it here (there is no UI-free alternative).
if (-not $NoMiuiGrant) {
    Invoke-Adb -Arguments @('shell', 'appops', 'set', $config.Package, '10020', 'allow') -AllowFailure | Out-Null
}
$miuiOp = Invoke-Adb -Arguments @('shell', 'appops', 'get', $config.Package) -AllowFailure |
    Where-Object { $_ -match 'MIUIOP\(10020\)' }
Write-ExNote ('MIUI show-when-locked (MIUIOP 10020): {0}' -f (($miuiOp -join '').Trim()))

$version = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $config.Package) -AllowFailure |
    Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
$postNotifications = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $config.Package) -AllowFailure |
    Where-Object { $_ -match 'POST_NOTIFICATIONS: granted=' } | Select-Object -First 1

Write-ExArtifact -Name '01-install.txt' -Lines (@(
        '# 01-install',
        ('apk      : {0}' -f $apk),
        ('install  : {0}' -f ($result.Output -replace "`r?`n", ' | ')),
        ('usb-dialog confirmed : {0}' -f $result.DialogConfirmed),
        ('version  : {0}' -f ($version -join '').Trim()),
        ('post-not : {0}' -f ($postNotifications -join '').Trim())
    )) | Out-Null

Write-ExNote ('installed {0}' -f (($version -join '').Trim()))
return $true
