@echo off
REM RearCue PC bridge launcher (issue #171). The scheduled task runs THIS file, so no
REM quoting games live in the task action:
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
REM ASCII ONLY -- do not put non-ASCII text in this file. cmd.exe decodes .cmd files with
REM the OEM codepage (GBK here), so UTF-8 Chinese in a REM line gets split into stray
REM commands and the launcher fails (measured 2026-09-29: "'...' is not recognized").
cd /d "%~dp0"
powershell.exe -NoProfile -WindowStyle Hidden -Command "$env:BRIDGE_LOG='%~dp0bridge.log'; & node '%~dp0bridge.mjs' 2>&1 | Out-Null"
