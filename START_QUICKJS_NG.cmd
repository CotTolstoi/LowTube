@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0JS_RUNTIME_SMOKE.js" goto missing
if not exist "%~dp0LowTube.exe" goto missing
if not exist "%~dp0tools\qjs.exe" goto missing
echo Checking existing QuickJS-NG...
"%~dp0tools\qjs.exe" "%~dp0JS_RUNTIME_SMOKE.js"
if errorlevel 1 goto failed
set "LOWTUBE_JS_RUNTIME=quickjs"
set "LOWTUBE_LAUNCH_TIME=%TIME%"
start "" /wait "%~dp0LowTube.exe"
if exist "%LOCALAPPDATA%\LowTube\startup.txt" copy /y "%LOCALAPPDATA%\LowTube\startup.txt" "%LOCALAPPDATA%\LowTube\startup-quickjs.txt" >nul
exit /b
:missing
echo Extract the complete LowTube archive into a folder before starting.
pause
exit /b 1
:failed
echo Runtime preflight failed. LowTube has not been started.
echo Use START.cmd to return to your original QuickJS-NG runtime.
pause
exit /b 1
