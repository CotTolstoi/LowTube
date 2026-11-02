# Code map

- `src/Core.cs`: settings/data models, JSON facade, executable lookup, URL/format rules, process runner.
- `src/JsonCodec.cs`: bounded JSON serialization without System.Web dependency.
- `src/MainForm.cs`, `VideoGrid.cs`, `Ui.cs`, `Theme.cs`, `I18n.cs`: WinForms UI, content cards, themes, translation catalogs.
- The native main window, cards and theme use the 0.11.6 layout again. VideoGrid retains its bounded thumbnail cache and double-click playback. The optional web client and its local listener have been removed at the user's request.
- `src/Player.cs`, `PlayerTuning.cs`, `VideoSettingsForm.cs`: mpv lifecycle/IPC/options, advanced settings and job ownership.
- `Media.From` uses mpv's `lavf://` stream adapter for direct non-live googlevideo HTTP media only when yt-dlp's certifi CA bundle exists. FFmpeg uses 8 MiB bounded HTTP requests with the matching short-seek threshold; file-local mpv options explicitly verify TLS using that bundle. Live/HLS, unrelated hosts and absent CA bundle use the previous transport. The idle mpv process receives the stream options at startup and retains IPC reuse.
- Player Stop invalidates the start generation/session before async cleanup. Lua Stop messages include the closed session; a replacement also recognizes its previous session while Start is pending. Start cleanup remains inside its semaphore. start-file/end-file playlist entry IDs reject old replacement events; pending and cleared sessions reject late load/EOF events. MainForm clears recovery/queue ownership before awaits.
- External audio is supplied in each `loadfile` options map as the file-local `audio-files` option. Do not set the global runtime option: mpv retains runtime option changes across playlist file replacements. Staged playlist entries also explicitly apply their audio file locally. In-player N and OSC Next send a host queue-advance request, preserving LowTube's prepared-item/session ownership.
- OSC close uses that same Stop callback (script binding lowtube_osc/close-player), preserving idle mpv reuse. Player handles end-file reason quit before entry filtering. Windows JSON IPC drops shutdown, so disconnected listeners wait once, off-thread and at most 500 ms, for a normal exit code before cleanup. Normal exit cancels retained session/recovery ownership; unexpected process/IPC loss still raises PlaybackFailed. Failure ownership is captured before completing pending awaiters. Duplicate native closes are ignored after session/replacement ownership is cleared.
- `player/lowtube.lua`, `lowtube_osc.lua`: mpv integration and player controls. Inspect actual keybindings before editing.
- `src/PlaybackLibrary.cs`, `WorkspaceFeatures.cs`, `LibraryFeatures.cs`: resume/queue/local library/UI workflows.
- `src/StreamContinuity.cs`, `ContinuityModels.cs`: bounded recovery, continuity helpers and model rules.
- `src/StreamOpenRecovery.cs`: one bounded fresh-link retry during initial playback/settings reload, typed mpv stream-open failures, format/log diagnostics with URL and header filtering. Backend.InvalidateStreamCache removes only the failed URL/context. Existing mid-playback recovery budget is separate and unchanged.
- `src/FormatCache.cs`, `FeedCache.cs`, `BackgroundFeeds.cs`, `LocalFeeds.cs`, `ContentFeeds.cs`, `Paging.cs`: extraction/cache/feeds/background paging.
- FormatPicker chooses the highest positive available height at or below Settings.Height after FPS/codec/audio eligibility checks; yt-dlp order breaks ties, separate streams are preferred at equal height. A missing requested resolution does not require another extraction or change saved limits.
- `src/InnerTube.cs`, `InnerTubeJson.cs`: bounded HTTP catalogue client, WEB configuration bootstrap, session authentication, renderer normalization and continuation paging. Backend catalogue methods route here; Resolve/Download remain on yt-dlp. Exact metadata uses watch-page JSON without retaining streamingData. Saved sessions are decrypted in memory; legacy browser mode reuses the explicitly authorized local cookie export only.
- Filtered recommendation/history pages maintain per-target/per-kind sliding windows of 5000 matching items and continuation tokens. Results beyond 5000 are supported; a single incoming native page can temporarily increase retained entries to 10000 while protecting the requested boundary. Evicted pages rebuild; the existing 64-request operation budget and resumable failure handling remain.
- `InnerTubeJson.Lockup` accepts a valid video ID from modern lockup cards even if YouTube omits `contentType` or changes its video enum label; Shorts continue through their dedicated renderer and playlist/podcast cards remain separately classified.
- `src/InnerTubeFailures.cs`: typed private-safe failures, transient continuation recovery policy and shared 429/503 cooldown. Cooldown blocks HTTP only; cached pages may be read. No automatic retry/timer. Test clocks and transports allow deterministic failure tests without real user sessions.
- `src/SavedSession.cs`, `BrowserDiscovery.cs`: local saved sessions and browser discovery/import. They are security-sensitive code paths.
- Startup refresh only uses the previously selected profile and existing saved session, checks that at least one valid sign-in cookie matches, rejects changed stamps and preserves old data on failure/cancellation; unchanged cookie sets do not rewrite the session. A changed session schedules one bounded background subscription sync, with old cache kept under its original session stamp.
- `src/GoogleAccount*.cs`: older OAuth implementation exists; its presence is not proof that frictionless Google sign-in for public distribution is solved. Current agreed media-access method is a saved YouTube session.
- `src/DownloadManager.cs`, `DownloadForm.cs`: download workflow and manager.
- `src/Equalizer*.cs`, `AudioForm.cs`: equalizer and audio UI.
- `src/StartupTrace.cs`, `PlaybackTrace.cs`: startup/playback diagnostics.
- `benchmark/`: separate executable referencing LowTube.exe. Version 1.2 supports cases/checkpoints/worker isolation.
- `hybrid/Hybrid.cs`: experimental two-GOP scheduling, fixed frame pools, FFmpeg CPU/D3D11VA workers, ordered YUV4MPEG2 stdin into mpv; 60-second local CFR H.264 experiment.
- `hybrid/Compare.cs`: five playback + five no-display controls; one comparison report and per-case watchdog.
- `hybrid/metrics.lua`: periodic mpv samples for comparison.

Build dependency order: LowTube.exe -> LowTube.Benchmark.exe, LowTube.Hybrid.exe, LowTube.Hybrid.Compare.exe.
AssemblyVersion stays 0.11.0.0 while AssemblyFileVersion is 0.12.2.0. Preserve binary/API compatibility intentionally. Existing Backend public method signatures are retained; VideoMetadata, targeted stream cache invalidation and the explicit catalogue-client constructor overload are additive.
No .sln/.csproj existed in the current project. Scripts invoke C# compiler directly; do not assume a modern dotnet-build app.
