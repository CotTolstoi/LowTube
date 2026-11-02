@echo off
cd /d "%~dp0.."
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe /platform:x64 /optimize+ /codepage:65001 /out:LowTube.Benchmark.exe /r:LowTube.exe /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.Management.dll benchmark\*.cs
if errorlevel 1 pause
