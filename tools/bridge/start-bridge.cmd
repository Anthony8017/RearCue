@echo off
REM RearCue PC bridge launcher + retry engine (issue #171, retry #189). The scheduled
REM task runs THIS file, so no quoting games live in the task action:
REM   * `-Command "& '...'"` in the task action gets its quotes eaten by Task Scheduler,
REM     leaving an empty argument and the task exits 1 (measured 2026-09-29).
REM This file starts the bridge itself via `powershell -WindowStyle Hidden`:
REM   * no black console flash at logon (the tray icon next to it is the interface);
REM   * the bridge writes bridge.log ITSELF (BRIDGE_LOG below). Do not redirect stdout into
REM     that same file: Out-File holds an exclusive lock, the bridge's own appends fail, and
REM     Node's pipe buffering delays what does land (both measured 2026-09-29).
REM     stdout/stderr therefore go to Out-Null -- the bridge logs everything it needs, and
REM     still logs on SIGINT/SIGTERM/SIGHUP itself.
REM   * the bridge dies with its parent, so no orphan process is left behind.
REM
REM Retry engine (#189): bridge exit code 0 = clean stop (a human stopped it: Ctrl+C signal,
REM tray "exit" menu) -> exit /b 0, NO retry. Non-zero = retryable (self-shutdown exits 75,
REM a crash is non-zero anyway) -> wait 30s and run the bridge again, at most 3 retries
REM (4 runs total); exhausted -> exit /b 1 and stay down.
REM Manual-stop flag (review fix for #189): the tray kills the node process HARD (exit code 1),
REM which alone would look retryable. When the owner picks "exit bridge" the tray writes
REM bridge.log.stopflag next to the log BEFORE killing; we honour it as a clean stop and delete
REM it. The bridge itself clears a stale flag on startup, so a leftover flag can never mask a
REM later crash. This also covers the manual-topology hole: when the owner runs THIS launcher
REM by hand there is no scheduled task to stop, the flag is the only thing that keeps the
REM retry engine from resurrecting a manual exit.
REM The 30s rhythm lives HERE, not in Task Scheduler settings: the scheduler rejects a 30s
REM restart interval at registration ("task XML ... value malformed or out of range
REM (33,25):Interval:PT30S", minimum PT1M, measured 2026-09-30), so task-level restart is
REM disabled in enable-autostart.ps1 and the retry belongs to this wrapper.
REM RCU_BRIDGE_RETRY_MS overrides the gap (harness accelerates it; default 30000).
REM ASCII ONLY -- do not put non-ASCII text in this file. cmd.exe decodes .cmd files with
REM the OEM codepage (GBK here), so UTF-8 Chinese in a REM line gets split into stray
REM commands and the launcher fails (measured 2026-09-29: "'...' is not recognized").
cd /d "%~dp0"
if not defined RCU_BRIDGE_RETRY_MS set "RCU_BRIDGE_RETRY_MS=30000"
if not defined BRIDGE_DSH_ROSTER set "BRIDGE_DSH_ROSTER=1"
set /a RCU_BRIDGE_RETRIES_LEFT=3
:rcu_run
powershell.exe -NoProfile -WindowStyle Hidden -Command "$c = 1; $env:BRIDGE_LOG='%~dp0bridge.log'; & node '%~dp0bridge.mjs' 2>&1 | Out-Null; if ($LASTEXITCODE -ne $null) { $c = $LASTEXITCODE }; exit $c"
if %ERRORLEVEL% EQU 0 exit /b 0
if exist "%~dp0bridge.log.stopflag" (
    del "%~dp0bridge.log.stopflag" 2>NUL
    exit /b 0
)
if %RCU_BRIDGE_RETRIES_LEFT% LEQ 0 exit /b 1
set /a RCU_BRIDGE_RETRIES_LEFT-=1
powershell.exe -NoProfile -Command "Start-Sleep -Milliseconds %RCU_BRIDGE_RETRY_MS%"
goto :rcu_run
