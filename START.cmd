@echo off
cd /d "%~dp0"
if not exist "%~dp0LowTube.exe" (
  echo Run SETUP.cmd first.
  pause
  exit /b 1
)
set "LOWTUBE_LAUNCH_TIME=%TIME%"
start "" "%~dp0LowTube.exe"
