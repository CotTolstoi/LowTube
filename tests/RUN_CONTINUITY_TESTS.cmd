@echo off
cd /d "%~dp0.."
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:exe /platform:x64 /out:tests\ContinuityTests.exe /r:System.Security.dll /r:System.Xml.dll /r:System.dll /r:System.Core.dll src\JsonCodec.cs src\StartupTrace.cs src\PlaybackTrace.cs src\LocalFeeds.cs src\Core.cs src\FormatCache.cs src\SavedSession.cs src\BrowserDiscovery.cs src\ContentFeeds.cs src\FeedCache.cs src\Paging.cs src\Equalizer.cs src\PlayerTuning.cs src\Player.cs src\I18n.cs src\PlaybackLibrary.cs src\DownloadManager.cs src\ContinuityModels.cs tests\ContinuityTests.cs
if errorlevel 1 goto end
tests\ContinuityTests.exe
:end
pause
