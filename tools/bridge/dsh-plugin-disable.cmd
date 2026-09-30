@echo off
rem Double-click helper: remove the RearCue read-only plugin from the dsh profile
rem so dsh always boots clean. Re-enable later with dsh-plugin-enable.cmd.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dsh-plugin-toggle.ps1" -Action disable
echo.
pause
