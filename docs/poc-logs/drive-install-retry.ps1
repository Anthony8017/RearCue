# drive-install-retry.ps1 -- foreground retry of the debug APK install (issue #50 re-verification).
# The background install round died on MIUI's USB install dialog (INSTALL_FAILED_USER_RESTRICTED)
# after the signature-mismatch uninstall; this rerun answers the dialog and judges by `pm list`.
# ASCII-only source: Windows PowerShell 5.1 decodes BOM-less .ps1 as ANSI/GBK.

$ErrorActionPreference = 'Continue'
Import-Module 'C:\Users\13691\Desktop\RearCue\tools\ex\ExCommon.psm1'
New-ExDeviceSession -Name 'install-retry' | Out-Null
Set-ExScreenAwake
Invoke-ExKeyguardDismiss | Out-Null

$apk = 'C:\Users\13691\Desktop\RearCue\app\build\outputs\apk\debug\app-debug.apk'
$ok = $false
for ($attempt = 1; $attempt -le 3 -and -not $ok; $attempt++) {
    Write-ExNote ('install attempt {0}' -f $attempt)
    $r = Invoke-ExInstallApk -Apk $apk
    Write-ExNote ('success={0} dialogConfirmed={1}' -f $r.Success, $r.DialogConfirmed)
    if ($r.Output) { $r.Output | Select-Object -Last 2 | ForEach-Object { Write-ExNote ('  ' + $_) } }
    if ($r.Success) { $ok = $true } else { Start-Sleep -Seconds 3 }
}
$installed = ((Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', 'com.rearcue.poc') -AllowFailure) -join '')
Write-ExNote ('pm list: {0}' -f $installed)
if ($ok -and $installed -match 'com.rearcue.poc') { Write-ExNote 'INSTALL-OK'; exit 0 }
Write-ExNote 'INSTALL-FAIL'
exit 1
