@echo off
cd /d "%~dp0.."
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe /platform:x64 /out:tests\GoogleTests.exe /r:System.Security.dll /r:System.Xml.dll /r:System.dll /r:System.Core.dll src\JsonCodec.cs src\StartupTrace.cs src\PlaybackTrace.cs src\LocalFeeds.cs src\Core.cs src\FormatCache.cs src\SavedSession.cs src\Player.cs src\I18n.cs src\GoogleAccount.cs tests\GoogleTests.cs
if errorlevel 1 goto end
tests\GoogleTests.exe
:end
pause
