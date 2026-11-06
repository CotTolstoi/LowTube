# State and next work — 2026-11-06

## Playback format selection diagnostics — 0.12.3

The playback format picker distinguishes an extractor response with no usable video stream from formats excluded by the user's saved height, 30 FPS, or H.264 limits. Deterministic policy mismatches now report the specific restriction and do not trigger a redundant yt-dlp extraction; an empty/unusable stream response still gets one bounded fresh metadata attempt. The 0.12.4 docked UI remains parked in the sibling `new-ui-experiment` worktree on `experiment/new-ui-0124`.

## Playlist audio isolation and manual next — 0.12.3

External audio streams are now passed as per-file `audio-files` options on mpv `loadfile`, rather than changing a global runtime option that survives playlist replacements. Empty external-audio lists are passed for video files without a separate track. The mpv playlist-next OSC button and the `N` key now request the next LowTube queue item, so navigation does not depend on whether mpv has already staged a prefetched entry; the main-window Next in queue action remains. All four EXEs build; 14 targeted playback-selection checks and the EN/UK/RU localization audit pass. The full `Run-Checks.ps1 -WindowsMedia` run stops at GoogleTests because loopback sockets are denied in this environment; the native mpv IPC fixture times out before readiness, and optional Lupa tests were unavailable. Real beta-account playlist playback and audible sync remain unverified. AssemblyFileVersion 0.12.3.0.

## Long filtered recommendation/history lists

Filtered pages now have independent bounded continuation caches per target and kind. Matching items are counted before paging, retaining a sliding window of 5000 entries; older pages can be rebuilt and sequential navigation can pass 5000. A single native page can temporarily raise the count to at most 10000 before trimming, preserving the requested boundary. The existing 64-request per-operation budget, cancellation and transient-failure resume remain; an explicit retry continues after the budget. All four executables and Run-Checks.ps1 -WindowsMedia pass; synthetic cases cover over 5000 filtered results, independent filters, large native pages, failure/resume, and sparse pages. Actual long signed-in feeds and old hardware were not tested. User reports automatic session refresh, format selection and setup currently work; Google Drive is the desired direction for future library backups, not yet implemented.

## Automatic YouTube session refresh

On startup, when a saved session and a previously selected browser profile are present, LowTube attempts one local yt-dlp browser-cookie export with a 15-second cancellation timeout. It does not discover another profile or make a network call during this import. New sign-in cookies must be valid and share at least one matching sign-in cookie with the old session; cancellation, changed stamp, failed export or unverified identity keeps the existing session. Identical filtered cookies are not rewritten. If the session changes, an existing bounded background feed job refreshes account channels/subscriptions and only saves after both requests succeed and the session stamp remains current; stale cached account data is not rebound to the new stamp. A first import, missing profile or entirely rotated sign-in cookies requires the user to connect in Account. All four EXEs and Run-Checks.ps1 -WindowsMedia pass, including synthetic refresh/cancellation/account-mismatch tests; no real browser session or signed-in feed was accessed during tests.

## Browser remote removed

At the user's request the optional web client, its local listener and launch button were removed. Native WinForms, local browser-session import and Google OAuth callback code remain. Previous release notes and archives are historical and may describe or contain the former web client.

## Direct YouTube buffering fix

For the reported video yiKjQuYVgcY, the user reported 16,884 bytes/s while the video bitrate was 251,908 bits/s; mpv was paused for cache. Guest tests on the same machine reproduced a marked difference between bounded HTTP range requests (2 MiB in 0.14 s) and unbounded sequential reads (344,064 bytes in 6.22 s). Direct non-live googlevideo videoplayback streams with http/https protocol now use mpv's FFmpeg stream adapter with 8 MiB bounded requests and verified TLS using the installed yt-dlp certifi bundle. Missing bundle, other hosts, HLS and live streams retain the previous transport. No cookies or media were saved. A controlled localhost fixture confirmed complete bytes across 8 MiB boundaries. Guest real-video tests confirmed file-local verified TLS, mpv IPC load, selected separate audio and a seek to 120 seconds; no GPU/audio device or authenticated playback was tested. Four executables and Run-Checks.ps1 -WindowsMedia pass. Actual user session throughput and older hardware remain to be checked.

## Buffer contention and diagnostics

Automatic background feed requests are cancelled or deferred while the current player reports an active buffer pause; manually requested feeds retain priority. The decision expires after 12 seconds without current telemetry and requires an active matching playback session. Player diagnostics now include cache-speed, video/audio bitrate and playback speed for comparison during a stall. All four executables and Run-Checks.ps1 -WindowsMedia pass; the live network cause and actual throughput improvement have not been verified. A real stall report is still needed.

## Playback format cache and buffering

Resolve no longer stores freshly extracted metadata after a failed format selection. When an initial selection fails with cached or newly extracted metadata, it is invalidated and re-extracted once, with cancellation and the user's height/FPS/codec limits preserved. Explicit fresh extraction does not recursively retry. During an actual cache pause, pending next-item preparation is cancelled so its prefetch does not compete with the active stream; the existing bounded stall recovery remains. Build-All and Run-Checks.ps1 -WindowsMedia pass, including native mpv stop and open fixtures. These fixtures do not establish real YouTube throughput or reproduce the reported missing formats; settings and diagnostics during a real failure are still needed.

## Consistent recommendation and history pages (historical; 5000 limit lifted)

Recommendation and YouTube history pages now select matching, available entries before slicing, preserving ordering across InnerTube continuations and reporting errors instead of displaying incomplete pages. Local history already filters before paging. A final page can contain fewer entries. The filtered-view scan retains up to 5000 raw entries and 64 additional continuation requests; beyond this limit it reports an error instead of producing incorrect pages. Existing unfiltered Backend.Browse and playlist-queue APIs remain unchanged. Four executables build and Run-Checks.ps1 passes, including a mixed video/Shorts/unavailable fixture. Live signed-in feeds and GUI navigation remain unverified.

## Page navigation

InnerTube BrowsePage returns visible items and actual continuation availability without extra requests; the existing Browse API remains. Native and web navigation use this state, including cached recommendation pages. Subscription feeds can advance while cached rows remain or any source has not confirmed its end. A failed catalogue page leaves the existing results in place; uncached recommendation page changes wait for success before replacing the previous page. Empty results are distinguished from request errors. Four executables rebuilt; offline checks include exact-size final pages and retained continuation rows. Live account and GUI navigation remain unverified.

## Google sign-in through a browser session

The account dialog now offers a visible button to open YouTube in the default browser, followed by the existing local browser-profile session import. Google credentials are entered on YouTube; the existing import, local encryption, account feed synchronization and media settings remain in use. All four executables and Run-Checks.ps1 pass; a real account and browser interaction have not been tested.

## Recommendation renderer compatibility 0.12.2

InnerTube's modern `lockupViewModel` parser previously discarded otherwise valid video cards if YouTube omitted `contentType` or changed its enum value. Shorts use a separate renderer and remained visible, which could leave recommendation pages showing Shorts without ordinary videos. The parser now accepts a lockup as a video when it has a valid 11-character video ID and the type is absent or identifies video; playlist/podcast types keep their existing validation. A mixed recommendation fixture verifies that ordinary videos and Shorts both survive. All four EXEs build and `Run-Checks.ps1` passes; no real signed-in feed was accessed. AssemblyFileVersion 0.12.2.0; AssemblyVersion stays 0.11.0.0.

## Restored prior interface 0.12.1

At user request restored the 0.11.6 WinForms shell, cards and theme, including double-click playback and the always-visible playback settings/log. The later Web client button, listener and disposal remain. Removed obsolete YouTubeShell/YouTubeUi sources and the UI tests specific to that design; historical patch and screenshots remain. No backend or player changes. Run-Checks.ps1 -WindowsMedia and the four-executable build validate the rollback; the actual GUI and physical GPU/audio remain to be checked separately. AssemblyFileVersion 0.12.1.0; AssemblyVersion remains 0.11.0.0.

## Optional browser remote (historical; removed)

Expanded the opt-in browser client to include search and content filters, recommendations, local and YouTube history, local and YouTube subscriptions, channel browsing, playback queue management, local playlist CRUD/playback, download list controls and adding downloads with saved defaults, plus player controls for pause, relative seek, absolute scrub, stop and volume. YouTube session import/account settings remain in the desktop Account dialog. The responsive page is served by a short-lived local TCP listener bound only to 127.0.0.1; a per-run random access key protects the GET-only API and HTML responses have a restrictive CSP. Video cards load up to 60 lazy public thumbnails from `i.ytimg.com`; no saved-session credentials or referrer are sent. The page uses existing backend/state paths and polls only loopback player state every 1.8 s. The listener stops with the app or when the button is pressed again. Build-All.ps1 successfully rebuilt all four executables. No Windows GUI/browser interaction or real playback runtime was exercised in this environment.

## YouTube-style native interface 0.12.0

User requested a YouTube-like interface. Kept C# 5 / .NET Framework 4.8 WinForms and existing playback/catalogue architecture. Added a native painted logo/icons/buttons, top search, collapsible sidebar, content chips, rounded responsive thumbnail cards with channel initials and overflow actions. Decoder settings and bounded log remain available through Playback options / Details toggles. All 15 navigation actions, cards/list selection, queue, downloads, account/session, equalizer, settings and EN/UK/RU themes remain. Single thumbnail click plays once; title double-click and keyboard Enter remain supported. No autoplay or new catalogue/media background requests were introduced.

All four EXE rebuilt together. 515 existing regular checks, 173 isolated native WinForms UI checks and 23 mpv fixture checks pass. Localization audit: 509 entries, 412 referenced keys. UI checks cover 1280x840 and 980x680, light/dark, en/uk/ru, navigation collapse/restore, option/log access, filter model, list/card selection and activation/context interactions. Actual DrawToBitmap previews use synthetic artwork and titles, no account/media/network or user library. Optional Lupa sampler still skipped. Physical high-DPI monitor behavior and old-laptop GPU/audio/smoothness are not verified. See YOUTUBE_UI_VALIDATION_UK.md.

Private MainForm(bool preview) is only an offline UI-test construction path: skips settings/library/download reads, startup writes, Shown startup, background timers and mpv preparation; thumbnail networking disabled. Public MainForm() retains normal initialization. No runtime dependencies or settings schema changes. Current AssemblyFileVersion 0.12.0.0, AssemblyVersion stays 0.11.0.0. Latest cumulative patch: LowTube_0.12.0_patch_2026-10-21.zip; earlier full 0.11.6 archive remains historical.

## Approved local cleanup — 2026-10-17

After reviewing the audit and JS/hybrid follow-up, the user authorized removal of disposable files. Deleted 256 files (216,871,705 bytes / 206.82 MiB) and 16 empty directories: installer cache, temporary checks/probes/packagers, compiled Test.*.exe, four old cumulative patch archives, optional ffplay/FFmpeg SDK/docs/mpv docs, and launchers/README for absent Deno/QuickJS Classic runtimes. No C# or build-script changes; all four current executables, latest 0.11.6 patch, hybrid sources/launchers, test sources, historical reports/checksums/licenses and user data remain.

QuickJS-NG was retained: MainForm's startup dependency list still unconditionally requires qjs, so deleting it would prompt full Setup despite the default Node runtime. This corrects the earlier optional-removal assessment. Node, yt-dlp, QuickJS, mpv, ffmpeg and ffprobe passed local version/smoke checks after cleanup; no network/media/GPU test or rebuild was needed. All 348 pre-existing files outside the removal list retained size/timestamp during deletion. Exact receipt: CLEANUP_RESULT_2026-10-17.json; human report: CLEANUP_DONE_2026-10-17.md. Setup may recreate download-cache and optional vendor files on a later install.

## OSC close / explicit quit patch 0.11.6

User still observed reopening after closing the player. The 0.11.5 fix missed the custom OSC close button, whose callback still executed mpv quit. Reproduced on 0.11.5: quitting a loaded local fixture generated PlaybackFailed rather than Stopped. That event can trigger MainForm recovery. The button now calls the same session-aware Lua Stop as Q/CLOSE_WIN; its exact callback is exposed as a named script binding and exercised with the actual shipped OSC loaded.

Player handles end-file reason quit before entry filtering, clears current/replacement/prepared ownership and cancels pending Start. Duplicate close/exit signals do not report Stop twice. Windows JSON IPC does not forward shutdown; if the pipe closes after an idle quit, one bounded off-thread WaitForExit(500) reads exit code 0 before process cleanup and cancels retained recovery ownership. A nonzero exit / unexpected disconnect remains a failure. IPC failure ownership is captured before completing awaiters so their cleanup cannot erase the failure callback.

All four executables build; 515 regular checks plus 23 native Windows mpv checks pass. New cases include the actual OSC close callback, raw quit with a prepared next item, quit during delayed on_load, idle quit after EOF and forced process loss. Native fixtures use null audio/video and no network/user data; physical mouse/window and real GPU/audio behavior remain separate validation. See OSC_CLOSE_VALIDATION_UK.md.

## Playback close/quality patch 0.11.5

User reported occasional reopening after closing video and requested nearest-lower resolution fallback. Native close now invalidates the Player start generation, clears session/prepared tickets and cancels a pending load. Lua tags Stop with its session; stale Stop/prepared-start and old playlist-entry end events cannot affect a new session. MainForm cancels recovery/queue/playback work before awaiting cleanup and rejects stale EOF/recovery/adoption. Failed Start cleanup finishes under the gate rather than scheduling a delayed Stop that could hit the next Start.

FormatPicker explicitly ranks playable formats by descending positive height within the selected cap, preserving FPS/H.264 restrictions and separate-stream preference at equal height. A higher combined format can beat a lower separate stream. Saved maximum quality is unchanged. If no compatible stream exists at or below the cap, the existing explicit error remains.

All four executables build. 515 regular checks and 14 native Windows mpv checks pass (including close during a deliberately delayed local load, no retry/reopen, stale notifications, explicit restart and natural prefetch transition). No network/account requests or real audio/GPU/window-driver validation in this fixture. See PLAYBACK_STOP_VALIDATION_UK.md.

## Stream opening patch 0.11.4

User reported an mpv stream-open failure. Their diagnostics showed two fresh-extraction failures and a later successful cached start. The supplied video now opens in two short saved-session tests with identical 399+140 formats for FPS30 on/off; original root cause remains unconfirmed. Guest probing encountered 429 and stopped. No user cookies, signed URLs or raw metadata were printed or packaged.

Added one bounded fresh-URL retry for initial playback and settings reload when AutoRecover is enabled, targeted failed-stream cache invalidation, typed/allowlisted mpv file_error reporting and URL-safe diagnostics. Output initialization failures and cancellation do not trigger a new extraction. Quality, buffers and startup acceleration remain unchanged. All four EXE build; 503 regular checks plus two real Windows mpv/Lua/named-pipe fixture checks pass. Headless stream opening is not GPU/audio/smoothness validation. See STREAM_OPEN_VALIDATION_UK.md.

## Reliability stage 0.11.3

The user deferred playback-start optimization and asked to plan and begin subsequent development. First stage delivered: typed catalogue failures, shared Retry-After cooldown for HTTP 429/503 without automatic retries, preservation of validated continuation progress after transient failure/cancellation/request budget, detached result objects and batch stop on service-wide failures. Invalid continuation schemas and mismatched watch metadata fail visibly. No changes to media startup, buffering or hybrid architecture.

Build-All and Run-Checks pass: 485 checks including 32 reliability checks; localization audit passes, optional Lupa sampler skipped. Nine real public guest YouTube scenarios pass. Private account, GUI, media/audio/GPU runtime validation remains outstanding. Roadmap and acceptance criteria for UI recovery, local-library backups and Windows release validation: DEVELOPMENT_PLAN_UK.md. Stable/hybrid launch paths and existing Backend signatures remain intact.

## Catalogue patch 0.11.2

User explicitly requested replacing yt-dlp everywhere except media playback/audio-only/downloads, and approved keeping its local browser-cookie export. Implemented InnerTube catalogue routing, memory-only protected-session access, bounded continuation caches, exact metadata enrichment without stream extraction and EN/UK/RU diagnostics. No new dependencies; stable and hybrid launch paths remain separate.

Build-All and Run-Checks pass on Windows: 452 checks plus localization audit; optional Lua check skipped (Lupa absent). Nine public guest scenarios exercise the actual YouTube service. Signed-in feeds have synthetic tests only; no real user cookies or account sessions were read. No mpv/audio/GPU runtime validation: media tools are absent from this snapshot. See INNERTUBE_VALIDATION_UK.md for implementation, bounds, commands and remaining work.

## Agreed direction

LowTube targets weak Windows 10 x64 PCs. Existing features must be preserved. Main media tools: yt-dlp, default Node.js JS runtime, mpv, FFmpeg. Saved YouTube browser session is the chosen authentication mechanism for media. Historical OAuth code/docs remain; review before any claims of account support for public release.
User requested a separate experimental CPU + D3D11VA decoder pipeline and measurement of its benefit. Stable playback has not been replaced.

## Hybrid history

0.1 allocated entire decoded GOP; 192 MiB/GOP limit blocked 1080p60 with long GOPs.
0.2 changed to fixed reusable frame pools (up to 64 MiB payload total, plus process/driver memory), incremental display and ffprobe progress.
0.3 fixed unavailable PeakWorkingSet64 after FFmpeg exit crashing playback; hardware proof now appears at first frame through mandatory hwdownload.
0.4 added five-mode automatic comparison and --batch workers; capped audio/video at 60 s.
0.5 adds per-stage timings and five no-display controls. Delivered, but no 0.5 run result has been provided.

## Most recent completed comparison: 1080p60 H.264

See reports/comparison_20261014_193746_003.txt (sanitized copy).

| Mode | Speed | Approx measured wall for ~60s media | mpv drops |
|---|---:|---:|---:|
| native CPU | .998x | 59.432s | 1252 |
| native D3D11VA | 1.000x | 59.462s | 2054 |
| pipeline CPU | .394x | 151.668s | 42 |
| pipeline D3D11VA | .355x | 167.871s | 130 |
| pipeline hybrid | .391x | 152.805s | 58 |

Hardware proof: native D3D11VA in all 60 samples; hardware pipeline 20/20 GOPs; hybrid 10/10 hardware GOPs. All five cases completed.
The pipeline maintains more frames by stalling. Low drop counts do not mean smooth video. Native playback runs close to 1x with heavy drops. Tests use decoder threads=1, so they are not the best tuned native configurations from the earlier broad benchmark.
The earlier broad benchmark (369 cases, 11 format groups, 30s/case) found H.264 720p30 D3D11VA at ~6.22% machine CPU with no drops vs ~18.07% software; 1080p60 remained poor. Results are single-pass measurements, not universal guarantees.

## Current prototype constraints

Local downloaded H.264 8-bit YUV420 CFR, square pixels, zero-start keyframe, first 60s. No URL playback/seek/quality change in experimental path. Two GOP workers; new FFmpeg process per GOP; accurate seek/preroll; CPU threads=1. D3D11VA copy-back -> YUV420 RAM -> stdout -> C# queues -> stdin -> mpv -> GPU. This is not zero-copy.
First 61s frame scan takes about 50s on the laptop. Full original timestamps/color metadata are not preserved in Y4M; HDR is rejected. No-display null controls retain hwdownload/format conversion; they do not measure GPU decoding in isolation.

## Next authorized work

1. Get the comparison_*.txt from 0.5. If unavailable, inspect code and explain what measurements are needed before asking for a long rerun.
2. Use STAGE_DECODER, STAGE_CONSUMER, STAGE_TOTALS, null-* and drain-* controls to locate bottlenecks. Times overlap across producers/consumer; do not sum them as elapsed wall time.
3. Distinguish true decode work from seek/preroll, FFmpeg launches, raw stdout transport, queue waits and mpv backpressure. decode_and_pipe_read_ms combines read and decode, not pure codec time.
4. Discuss a persistent-decoder/direct-memory implementation after the data identifies the useful change. No native libavcodec/shared-memory rewrite has been implemented yet.

## Other backlog (not automatic scope expansion)

Windows 7 32-bit / old Linux, then Android; browser-session UX/compatibility on more browsers; review Google/YouTube integration options. InnerTube catalogue integration is now included in 0.11.2; actual signed-in account validation remains to be done.
User wants no silent feature removal and discussion before major changes. Do not delete currently unused tools/features as part of cleanup without agreement.
