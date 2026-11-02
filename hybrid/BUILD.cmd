@echo off
cd /d "%~dp0.."
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe /platform:x64 /optimize+ /out:LowTube.Hybrid.exe /r:LowTube.exe /r:System.dll /r:System.Core.dll /r:System.Windows.Forms.dll hybrid\Hybrid.cs
if errorlevel 1 pause
