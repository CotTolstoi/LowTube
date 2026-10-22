LowTube 0.11.6 — Windows 10/11 x64 — application distribution without tools

INSTALLATION
Extract the entire archive to a writable folder. Run SETUP.cmd to download and
verify the required components, then START.cmd. .NET Framework 4.8 is required.
The installer in this distribution is SETUP.cmd, not setup.exe.
yt-dlp, Node.js 22, QuickJS-NG, mpv, FFmpeg and 7-Zip packages are NOT bundled.

UPDATING
Close LowTube. Copy the contents of the LowTube folder over your existing
installation, replacing files. Keep the existing tools folder. If its components
work, you do not need to run setup again. Settings, history and saved session
remain in your Windows user's local application data directory.

EQUALIZER
Choose Equalizer immediately below Settings in the left sidebar.
Tick Enable equalizer. Select Flat, Bass boost, Voice, Treble boost, Rock,
Classical or Custom. Ten bands cover 31 Hz to 16 kHz, adjustable from -12 to
+12 dB. Preamp is also adjustable. Moving a slider selects Custom; the custom
curve is retained when switching presets. Press Apply to hear and save changes.
The EQ applies to video and audio-only playback without restarting mpv.
Positive bands reduce input gain to provide headroom. If distortion is audible,
reduce Preamp further. EQ starts disabled and adds no EQ filter when bypassed.
It affects playback only; downloaded media files are not modified.

OTHER FEATURES
Separate video window, always on top, borderless mode and compact audio-only
window. F4 or right-click in the player opens playback settings, including
quality, decoder, renderer, speed and buffer. Quality changes reuse mpv;
changing renderer may require a restart. Preserved stream-format cache.
Downloads include available 1440p/2160p streams and audio-only formats.
Local and YouTube subscriptions, recommendations, live streams and playlists;
channel/content filters, local history and background feed caching.
Synchronize imports a supported browser's YouTube session. Browser restrictions
can prevent automatic import; support for every browser is not guaranteed.
A saved session may expire or require renewal. Never share session/cookie files.
Previous and next page buttons sit together. Settings accepts 1–250 items per
page. Language and theme are in Settings; new installations default to English.
Node.js 22 is the default JavaScript runtime. Optional runtime launcher scripts
require their matching separately installed components.

DIAGNOSTICS
Use Settings > Startup report or START_PLAYBACK_DEBUG.cmd; F8 marks a playback
problem. See PLAYBACK_DEBUG_UK.txt and HARDWARE_MODES.md for details.
Sources and build scripts are included. BUILD.cmd rebuilds LowTube.exe.
See docs/development/PROJECT_STATE_UK.md for development status and verification
guidance. Historical release notes are kept in docs/legacy.
