# Code map

- `src/Core.cs`: settings and data models, JSON facade, executable lookup, URL/format rules and process runner.
- `src/JsonCodec.cs`: bounded JSON serialization without a `System.Web` dependency.
- `src/MainForm.cs`, `VideoGrid.cs`, `Ui.cs`, `Theme.cs`, `I18n.cs`: WinForms UI, content cards, themes and translation catalogs.
- `src/Player.cs`, `PlayerTuning.cs`, `VideoSettingsForm.cs`: mpv lifecycle, IPC, options, advanced settings and process ownership.
- `player/lowtube.lua`, `player/lowtube_osc.lua`: mpv integration and player controls. Inspect actual key bindings before editing.
- `src/PlaybackLibrary.cs`, `WorkspaceFeatures.cs`, `LibraryFeatures.cs`: resume, queue, local library and UI workflows.
- `src/StreamContinuity.cs`, `ContinuityModels.cs`: bounded recovery, continuity helpers and model rules.
- `src/StreamOpenRecovery.cs`: bounded fresh-link retry during initial playback/settings reload, typed mpv stream-open failures and filtered diagnostics.
- `src/FormatCache.cs`, `FeedCache.cs`, `BackgroundFeeds.cs`, `LocalFeeds.cs`, `ContentFeeds.cs`, `Paging.cs`: extraction, caches, feeds and background paging.
- `src/InnerTube.cs`, `InnerTubeJson.cs`: bounded catalogue client, session authentication, renderer normalization and continuation paging. Catalogue methods use this client; media resolution and downloads remain on yt-dlp.
- `src/InnerTubeFailures.cs`: typed failures and transient continuation recovery policy. Cooldown blocks HTTP requests; cached pages remain available. There are no automatic retries or timers.
- `src/SavedSession.cs`, `BrowserDiscovery.cs`: local saved sessions and browser discovery/import. These are security-sensitive paths.
- `src/GoogleAccount*.cs`: legacy OAuth implementation; its presence does not prove public sign-in is configured or verified.
- `src/DownloadManager.cs`, `DownloadForm.cs`: download workflow.
- `src/Equalizer*.cs`, `AudioForm.cs`: equalizer and audio UI.
- `src/StartupTrace.cs`, `PlaybackTrace.cs`: startup/playback diagnostics.
- `benchmark/`: separate executable referencing `LowTube.exe`; supports cases, checkpoints and worker isolation.
- `hybrid/Hybrid.cs`: experimental two-GOP scheduling, fixed frame pools, FFmpeg CPU/D3D11VA workers and ordered YUV4MPEG2 input to mpv.
- `hybrid/Compare.cs`: playback and no-display controls, comparison reports and per-case watchdog.
- `hybrid/metrics.lua`: periodic mpv samples for comparisons.

## Compatibility and lifecycle notes

Build order: `LowTube.exe` → `LowTube.Benchmark.exe`, `LowTube.Hybrid.exe` and `LowTube.Hybrid.Compare.exe`.

`AssemblyVersion` remains `0.11.0.0`; `AssemblyFileVersion` tracks the application release. Preserve binary/API compatibility intentionally. Existing `Backend` public method signatures are retained; additions should be additive.

There is no `.sln` or `.csproj` in this project. The scripts invoke the C# compiler directly; do not assume it is a modern `dotnet build` application.

Player Stop invalidates the start generation/session before asynchronous cleanup. Lua Stop messages include the closed session, and playlist entry IDs reject stale replacement events. Recovery and queue ownership are cleared before awaiting cleanup. OSC close uses the same Stop callback so mpv can be reused while idle. Normal quit cancels recovery ownership; unexpected process/IPC loss remains a playback failure.

Format selection chooses the highest playable positive height at or below the configured limit after FPS, codec and audio checks. yt-dlp order breaks ties; separate streams are preferred at equal height. A missing requested resolution does not require another extraction or change saved limits.

Saved sessions are decrypted in memory. Legacy browser mode uses only an explicitly authorized local cookie export. Diagnostics must filter signed URLs and headers.
