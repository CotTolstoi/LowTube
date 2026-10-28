@echo off
setlocal
cd /d "%~dp0"
set "LOWTUBE_PLAYBACK_DEBUG=1"
set "LOWTUBE_JS_RUNTIME=node22"
if not exist "%~dp0tools\js\node22\node.exe" (
 echo Extract LowTube_Runtime_Node22_x64.zip into this folder first.
 pause
 exit /b 1
)
set "LOWTUBE_LAUNCH_TIME=%TIME%"
start "" /wait "%~dp0LowTube.exe"
explorer "%LOCALAPPDATA%\LowTube"
