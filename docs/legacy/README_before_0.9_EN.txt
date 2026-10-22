LowTube 0.7.0 — complete Windows 10/11 x64 distribution

Extract to a new folder and run START.cmd. Node.js 22 (default), yt-dlp,
mpv, FFmpeg/ffprobe, QuickJS-NG, Deno and classic QuickJS are included.
.NET Framework 4.8 is required. Settings/history/saved session are reused
from this Windows user's %LOCALAPPDATA%\LowTube; no user sessions are bundled.
SETUP.cmd reinstalls/updates components, installs Node22 and preserves other JS runtimes.

F4 / right-click → Video settings: auto, auto-copy, dxva2, dxva2-copy,
d3d11va, d3d11va-copy, nvdec, nvdec-copy, no. Active decoder shows actual use.
Direct DXVA2/D3D11VA select D3D11; direct NVDEC selects OpenGL. A renderer
change may restart mpv while preserving playback position. Existing preferences
are retained; new users default to auto. See HARDWARE_MODES.md.
Settings → Recommendations language controls guest topic search separately
from UI language. It is a preference, not a strict spoken-language filter.
Download → Download resolution includes 1440p and 2160p (4K). These presets
select MKV and remove download codec/FPS limits. Native streams only: no upscale
or video re-encoding. MP4 allows available AV1 MP4 streams when H.264 only is off.
Playback settings are independent. 1440p means QHD, not DCI 2K.
START_PLAYBACK_DEBUG.cmd enables diagnostics; F8 marks a playback problem.

This x64 build cannot run on a Lenovo S12 with the 32-bit Atom N270/GMA950.
A separate x86 implementation and dependency set would be required; GMA950 lacks
full H.264 hardware decoding. Newer x64 Atom GPUs depend on codec/driver support.

---- Previous feature reference / Довідка попередніх функцій ----
LOWTUBE 0.6 — WINDOWS 10/11 x64

NEW IN 0.6
- mpv starts in the background after LowTube's window is ready. No video,
  window or playback buffer is kept while idle. Close LowTube to release it.
- Quality changes reuse the same mpv process and preserve position, pause,
  volume and speed. The new stream still needs to buffer. A renderer change
  can require a new process.
- Formats share a RAM cache across quality choices, format dialogs and
  audio-only: up to 4 videos for 3 minutes. Live streams are not cached.
  Raw metadata budget: 8 MiB total, 4 MiB per entry; actual RAM use is higher.
  Settings > Clear stream cache forces extraction on the next request.
- Separate saved YouTube session: Account > Firefox / cookies settings >
  Save Firefox session (or Import saved session), then Save. The saved mode
  and use-for-playback checkbox are selected automatically after import.
  Firefox is used only when copying/updating its session, not on each play.
  Refresh this copy manually when it expires or YouTube invalidates it;
  there is no guaranteed renewal interval or guarantee against bot checks.
- The saved copy contains only YouTube cookies, encrypted with Windows DPAPI
  for the current Windows user. Temporary private cookie files exist while
  yt-dlp runs; normal exit deletes them, and later session use cleans up
  abandoned files after a crash. Delete saved session removes the local copy.
  Existing Firefox/file modes, Google OAuth and other features remain.

UPDATE FROM 0.5
Close LowTube, extract ALL files over your existing LowTube folder, allowing
replacement. Keep the existing tools folder. SETUP.cmd is only needed if
components are missing/outdated. Settings and history are outside the app folder.

HDD STARTUP • IN-PLAYER SETTINGS • CUSTOM BUFFER • SEEK GUARD
2026-10-11

NEW IN 0.5
YouTube-inspired header, left navigation and video cards with thumbnails.
Cards / List switches views without changing the selected video. The table
view remains available. Double-click or Enter plays the selected card;
arrow keys move the selection. Search, downloads, audio only, formats,
local subscriptions, diagnostics, languages, themes and manual buffers remain.
Thumbnails load only for visible cards, two requests at a time, with a
64-image RAM cap and no disk cache. Switch to List for a text-only view.
My history is now Local history. Existing history is retained.

Always on top in the main window changes the current player immediately
and is remembered for future playback. It also controls the compact audio
window. The same option is in F4 / right-click > Video settings > Apply.
The video player still opens in its own window.

Account now opens Google sign-in. One-time OAuth project setup is required:
see GOOGLE_SIGN_IN_EN.txt. Google subscriptions is a separate sidebar item;
it does not overwrite local subscriptions. Existing Firefox / cookies
settings are still under Account > Firefox / cookies settings.
Updating from 0.4 requires no component reinstallation.

UPDATE AN EXISTING INSTALLATION
Close LowTube. Extract this archive over the existing LowTube folder,
replacing application files. Keep your tools folder. Run LowTube.exe or
START.cmd; the title should show 0.6. For the HDD optimization, close the app
and run UPDATE_YTDLP.cmd ONCE. It installs the official unpacked yt-dlp x64
package with SHA256 verification, leaving mpv, FFmpeg and QuickJS unchanged.
Full SETUP.cmd is only needed for a fresh installation or a full tools update.
Keep the new player/lowtube.lua file; do not copy only the EXE.
Settings, browser-session paths, buffer selection and history are retained
in %LOCALAPPDATA%\LowTube.

FIRST INSTALLATION
Extract the whole archive to a writable folder, then run SETUP.cmd.
Windows x64, .NET Framework 4.8 and internet access are required.
No Visual Studio, Python or Node.js is required. The setup verifies component
SHA256 hashes and selects baseline x86_64 builds, never x86_64-v3.
LowTube and portable components do not require administrator access.
See installation troubleshooting below if a component fails to start.

LANGUAGE
Open Settings > Language: English, Українська, Русский; click Save.
English is the default on first launch. An existing language choice is kept.
The language and theme controls are together in Settings. Choose Theme >
Dark or Light and click Save; all open LowTube windows update immediately.
Windows file dialogs, scrollbars and mpv use their own system/player styling.
The choice is remembered. All LowTube captions,
messages, settings, account and download dialogs, and audio controls are
localized. Video titles, channel names and technical identifiers remain
original. yt-dlp/mpv/FFmpeg logs and Windows system dialogs use their own language.
The legacy test laptop profile button has been removed. Existing playback settings
are preserved. Fresh settings remain conservative: H.264, up to 30 FPS,
720px height, dxva2-copy and d3d11. There is no device-profile reset button.

SEARCH AND VIDEO
Enter a YouTube URL or search query and press Search / URL. Select a row,
then Video, or double-click it. Lists contain up to 25 entries per page;
search covers up to 100 results. A selected row takes priority over the
search box; run a new search / URL to select another video.

Video uses a regular mpv window: Space pauses, F switches fullscreen,
arrows seek, I / Shift+I show statistics, Q closes the window.
Pause, seek and stop are also available in LowTube.
F4 or right-click IN mpv opens Video settings: maximum quality, FPS, H.264,
decoder, renderer, speed, safe seeking and all buffer values. Apply reloads the stream in the same mpv process
at the current position, preserving pause and volume. Cancel keeps
playback unchanged. The same dialog is available from Video settings in LowTube.
Right-click now opens settings; use Space or the OSC button to pause.
The main height, H.264 and FPS controls apply to PLAYBACK. If 720p is only
available at 60 FPS, the 30 FPS cap may select 480p. Disable the cap to try
higher quality, bearing in mind the extra load on an old PC.

AUDIO ONLY
Audio only opens a small window with one preview image, title, channel,
position, volume, pause and seeking. No regular mpv video window opens.
Only an audio stream and one JPEG thumbnail are fetched: no video stream,
video decoding or embedded browser. Closing the compact window or pressing
Stop stops the audio; minimizing does not. A missing preview shows a text
placeholder without interrupting playback. At the end, the window remains
open with Playback ended. Start Audio only again from the main window to replay.

DOWNLOADS
1. Select a video and click Download. Wait for its available streams.
2. In the separate dialog choose Video + audio or Audio only.
3. For video choose MP4, MKV or WebM and an actual resolution / FPS / codec.
   Source labels and format IDs are also shown. For audio choose M4A, MP3
   or Opus and one available audio stream.
4. For MP3 choose 96–320 kbps. A higher bitrate cannot restore source detail;
   MP3 conversion uses CPU time.
5. Click Download. Progress appears in the main log; Cancel stops the job.
   Closing the options dialog before confirming does not download media.

MP4 uses H.264 + AAC, WebM uses available WebM codecs, and MKV uses the
chosen codec. MKV has its own H.264-only checkbox. The dialog's 30 FPS limit
is separate from playback and is off by default. Choices are remembered
when confirmed; playback quality is not changed.
M4A and Opus are saved without re-encoding; MP3 converts audio only.
Audio-only options never fetch a video stream, even for MP3 conversion.

The chosen native dimensions are preserved. A 1536×640 stream stays
1536×640: there is no stretching or upscaling. If the chosen stream is no
longer available, the download fails rather than silently choosing a lower
resolution. Different format IDs and MP3 bitrates get different filenames.
Completed files are not overwritten. Repeating the same choice can resume
.part files; older .part files with different names are not picked up automatically.
Choose the destination in Settings; Folder opens it. Download media you
have permission to save.

BUFFER AND DIAGNOSTICS
Settings and the in-player dialog now have numeric fields:
- Forward buffer: 5–3600 seconds.
- Forward memory: 16–1024 MiB per demuxer.
- Back buffer: 0–512 MiB per demuxer.
- Start/refill threshold: 1–30 seconds, never above the forward duration.
Existing 12/60/120-second choices keep their former 32/64/128 MiB caps.
Video and separate audio each have their own buffer; their memory use adds
up, plus decoder/renderer/app memory. For example, 128 MiB forward + 16 MiB
back may use up to roughly 288 MiB across two streams before other overhead.
The memory cap can stop prefetch before the requested duration is reached.
A 300-second buffer with a 2-second threshold does NOT wait five minutes
before starting. Media cache stays in RAM; disk cache is disabled.
Settings applies to the next playback; F4 > Apply reloads the stream in the current mpv process.

Safe seeking pauses sound until mpv reports video playback has restarted.
It covers the OSC timeline, keyboard and LowTube seek buttons. An existing
user pause stays paused. On a 30-second timeout, the player stays paused and
shows a message: press Space to override or seek elsewhere. This mode can
be disabled in F4 settings. mpv uses the audio clock and initial A/V alignment.
This addresses playback running ahead while seeking; it cannot guarantee
that every old GPU driver will recover correctly. If the issue remains,
open Player diagnostics DURING the problem and try Software (no) decoding
or F4 > Apply to restart the decoder from the current position.

HDD STARTUP AND MEASUREMENTS
The app no longer loads System.Web for JSON. History and tool checks occur
after the window is shown; process launch no longer blocks the UI. yt-dlp's
unpacked distribution avoids extracting its embedded runtime on every launch.
Selected stream metadata is held only in RAM for up to 3 minutes / 4 entries.
Replaying the same profile or changing only buffer/decoder settings can reuse
it. Quality/account changes use a different cache entry. Settings > Clear
stream cache forces fresh extraction if a cached URL fails.
Settings > Startup report shows process-to-managed-entry, controls, first
paint, history, extraction, mpv launch and observed playback-restart timings.
A copy is saved in %LOCALAPPDATA%\LowTube\startup.txt, with no stream URLs
or cookies. This separates app/CLR/disk startup from extraction and buffering.
No specific startup time is promised without testing the target HDD.

Decoders include DXVA2, D3D11VA, automatic and software modes; output uses
D3D11 or OpenGL. Selecting a mode does not prove active hardware decoding.
Old hardware cannot gain support for new codecs through an app setting.
Player diagnostics takes two snapshots. hwdec-current = no means software
decoding; paused-for-cache = true means waiting for data. Compare dropped
frame counters and buffered time during a problem. Reports can be copied.
Audio-only playback supports diagnostics too; video properties may be unavailable.

WITHOUT AN ACCOUNT
Recommendations uses public YouTube search for a topic. Change Guest
recommendations topic in Settings (default: science and technology).
This is a topic discovery feed, not Google's personalized homepage.
Select a video and click Subscribe locally to follow its channel.
Channels lets you add a channel URL / @handle or remove a subscription.
Subscriptions refreshes recent public uploads, newest first. Next 25 pages
through the loaded list. Empty subscriptions opens the channel manager.
The channel list is saved only on this PC and is not synced with Google.
Up to 100 channels; two feed requests at once, on demand only. Public feeds
usually contain the latest 15 uploads per channel, not the entire archive.
Unavailable channels use cached results with a message in the log.
Recommendations and local subscriptions never read your browser cookies,
even when Use this session for search, playback and downloads is enabled.
Local data: %LOCALAPPDATA%\LowTube\subscriptions.json and feeds\ cache.

ACCOUNT
Sign in to YouTube in a normal Firefox window. Open Account and select the
Firefox session. A blank profile path means the default profile. For a
specific profile copy Root Directory from about:profiles. If cookies are
locked, close Firefox and retry. cookies.sqlite is not a cookies.txt file.
Alternatively, use your own Netscape cookies.txt. Enter passwords only on
Google's website. Cookies are used locally; do not share them.
By default the session is used only for YouTube history. An account checkbox
also enables it for search, playback and downloads.
Reading Google watch history is experimental. LowTube does not write watch
history to Google or manage Google subscriptions. Local history keeps the
last 500 playback starts. There is
no built-in OAuth login and no LowTube server.

COMPONENT UPDATES AND TROUBLESHOOTING
Close LowTube and run SETUP.cmd to update components. Verified archives
are kept in download-cache and rechecked before reuse. You can remove that
folder after successful installation to recover disk space.
YouTube can return HTTP 403, 429, bot checks or PO Token requirements.
Update components and, if needed, connect your own browser session.
No DRM bypass is provided. Never send cookies or a browser profile for support.

Older installer failures:
- ffprobe: use the complete yt-dlp FFmpeg win64 GPL shared package including
  ffprobe.exe and all companion DLLs. Trimmed builds without ffprobe do not work.
- mpv -1073741515 / 0xC0000135 means a required DLL could not load.
  The current mpv build imports vulkan-1.dll even with D3D11 output. Setup
  checks an x64 loader and can add a verified official runtime next to mpv.
  If its download is blocked, visit https://vulkan.lunarg.com/sdk/home#windows
  and install the Windows x64/x86 Runtime Installer (not the SDK). This
  system installer may require admin access. Alternatively, place the official
  VulkanRT-X64-1.4.363.0-Components.zip next to SETUP.cmd and retry.
  A loader does not add Vulkan hardware support to an old GPU. Other DLLs
  may also be responsible; see setup-diagnostics.txt if the error remains.
- Illegal instruction / 0xC000001D: check CPU compatibility. older dual-core CPU
  cannot run x86_64-v3 / AVX2 builds.

DEVELOPMENT AND LIMITS
BUILD.cmd compiles all src/*.cs using the .NET Framework compiler.
I18n.cs contains translations; AudioForm.cs implements the compact player;
DownloadForm.cs implements quality selection. tests/RUN_TESTS.cmd tests core logic.
See SOURCES.md for component origins and VALIDATION.txt for actual testing.
Native WinForms: no embedded browser, telemetry or
background component updates. Sponsor segments within videos are not removed.
LowTube is unofficial; future YouTube changes may break it.

Run START.cmd when measuring startup; it also timestamps the launcher before the process exists.
