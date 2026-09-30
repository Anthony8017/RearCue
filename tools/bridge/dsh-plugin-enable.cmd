@echo off
rem Double-click helper: put the RearCue read-only plugin back into the dsh profile.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dsh-plugin-toggle.ps1" -Action enable
echo.
pause
