param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
try {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $desktopPackage = Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1
    if (-not $desktopPackage) { throw 'Codex Desktop was not found.' }
    $desktopExe = Join-Path $desktopPackage.InstallLocation 'app\ChatGPT.exe'
    if (-not (Test-Path -LiteralPath $desktopExe)) { throw 'Codex Desktop executable was not found.' }
    $nodeExe = (Get-Command node.exe -ErrorAction Stop).Source
    $runtimeScript = Join-Path $scriptDir 'codex-shared-runtime.mjs'
    if (-not (Test-Path -LiteralPath $runtimeScript)) { throw 'Shared runtime script was not found.' }
    $shortcutPath = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Codex-RearCue.lnk'
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
    $shortcut.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
    $shortcut.Arguments = '//B //Nologo "' + (Join-Path $scriptDir 'start-codex-shared.vbs') + '"'
    $shortcut.WorkingDirectory = $scriptDir
    $shortcut.IconLocation = $desktopExe + ',0'
    $shortcut.Description = 'Codex shared desktop and RearCue conversations'
    $shortcut.Save()
    if ($PrepareOnly) { Write-Output ('Prepared: ' + $shortcutPath); exit 0 }
    $running = Get-Process -Name ChatGPT -ErrorAction SilentlyContinue | Where-Object { $_.Path -match '\\OpenAI\.Codex_[^\\]+\\app\\ChatGPT\.exe$' }
    if ($running) { throw '请先正常退出 Codex Desktop，再打开 Codex-RearCue 入口。正在运行的任务和手机桥均未被停止。' }
    & $nodeExe $runtimeScript --ensure
    if ($LASTEXITCODE -ne 0) { throw 'Codex shared runtime failed to start.' }
    $codexRoot = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
    $runtimePath = if ($env:BRIDGE_CODEX_RUNTIME_FILE) { $env:BRIDGE_CODEX_RUNTIME_FILE } else { Join-Path $codexRoot 'rearcue-shared-runtime.json' }
    $runtime = Get-Content -LiteralPath $runtimePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($runtime.version -ne 1 -or $runtime.url -notmatch '^ws://127\.0\.0\.1:[0-9]+/[a-f0-9]{64}$') { throw 'Invalid Codex shared runtime configuration.' }
    $env:CODEX_APP_SERVER_WS_URL = $runtime.url
    Remove-Item Env:CODEX_APP_SERVER_FORCE_CLI -ErrorAction SilentlyContinue
    Start-Process -FilePath $desktopExe -WindowStyle Normal
} catch {
    if ($PrepareOnly) { throw }
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Codex-RearCue')
    exit 1
}
