@echo off
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1"
if errorlevel 1 (
  echo Setup failed. Read the error above.
) else (
  echo Ready. Run START.cmd.
)
pause
