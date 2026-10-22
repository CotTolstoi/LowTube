@echo off
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1" -YtDlpOnly
if errorlevel 1 (
  echo Update failed. Existing components are kept. Read the error above.
) else (
  echo Ready. Start LowTube.exe.
)
pause
