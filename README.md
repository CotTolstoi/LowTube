# LowTube

LowTube is a lightweight native YouTube client for Windows, designed to stay usable on older x64 PCs. The stable desktop application is written in C# 5 and Windows Forms for .NET Framework 4.8.

The main line in this repository is LowTube **0.12.3**. Experimental interface and platform work lives on separate branches and is not part of the stable Windows release.

## Features

- Browse YouTube search, recommendations, subscriptions, channels, playlists, history, and Shorts.
- Use InnerTube for catalogue data and yt-dlp to resolve playable media streams and downloads.
- Play video and audio-only streams in mpv with quality, codec, frame-rate, buffering, and recovery settings.
- Keep a local queue, watch history, subscriptions, playlists, and playback position.
- Import a YouTube session locally from a supported browser profile or cookies file.
- Use English, Ukrainian, or Russian, with light and dark themes.
- Run the experimental CPU/GPU decoding tools separately from normal playback.

YouTube changes can affect catalogue access and stream extraction. Saved sessions and application data stay in the user's local profile; do not upload browser cookies, session files, OAuth credentials, or `%LOCALAPPDATA%\LowTube`.

## Install and run

On Windows 10 x64 with .NET Framework 4.8:

1. Get the project source from the `lowtube-main` branch of [CotTolstoi/LowTube](https://github.com/CotTolstoi/LowTube/tree/lowtube-main).
2. Run `SETUP.cmd`. It downloads the pinned media and JavaScript runtime dependencies listed in `tools/versions.json`, verifies their SHA-256 values, and builds the application.
3. Run `START.cmd`.

The large third-party tools are not stored in this source tree. Setup needs an internet connection. User settings, cookies, sessions, history, and downloads are stored outside the project under `%LOCALAPPDATA%\LowTube`.

## Build and checks

Use Windows PowerShell 5.1 and the .NET Framework 4.8 compiler:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-All.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Run-Checks.ps1
```

`Build-All.ps1` builds the four first-party executables together. `Run-Checks.ps1` builds and runs the automated checks; optional Lua checks are skipped if Lupa is unavailable. Real playback, audio output, GPU decoding, and older GPU drivers require separate Windows runtime validation.

Useful launchers:

- `START.cmd` — LowTube desktop app.
- `BENCHMARK.cmd` — playback configuration benchmark.
- `HYBRID_EXPERIMENT.cmd` and `HYBRID_BENCHMARK.cmd` — separate experimental decoding tools.

## Project notes

- The application targets Windows 10 x64. Windows 7, Linux, and Android are not supported by this stable desktop branch.
- The YouTube catalogue client uses internal InnerTube endpoints, which may change without notice.
- Browser session import is local. Never commit cookies, session files, OAuth secrets, local diagnostics, build outputs, or downloaded dependencies.
- See [README_UK.txt](README_UK.txt), [HARDWARE_MODES.md](HARDWARE_MODES.md), and `handoff/` for more details and validation notes.

## License

LowTube is distributed under the MIT License. See [LICENSE.txt](LICENSE.txt). Third-party tools retain their own licenses; see [SOURCES.md](SOURCES.md).
