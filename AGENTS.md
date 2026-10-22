# LowTube — coding notes

Read `docs/development/PROJECT_STATE_UK.md` and `docs/development/ARCHITECTURE.md` before substantial changes.

The stable Windows application is a C# 5 / .NET Framework 4.8 WinForms program. Keep it and the experimental hybrid pipeline separately launchable. The Linux app is an isolated .NET 8 / Avalonia project under `linux/LowTube.Linux`; don't pull Avalonia dependencies into the Windows project.

## Project constraints

- Communicate in Ukrainian. Preserve existing features and explain substantial architectural changes before making them.
- Target Windows 10 x64, including older CPUs without AVX/AVX2. Do not use x86_64-v3 dependencies.
- Windows 7 32-bit, older Linux systems and Android are not supported targets. The Linux preview is developed and validated separately on modern Linux; don't claim a distro or desktop support matrix without testing it.
- English is the default UI language. Keep English, Ukrainian and Russian catalogs and theme settings.
- Keep C# 5 compatibility and avoid new runtime dependencies without discussion.
- Maintain mpv reuse, IPC, resume position, queue, equalizer, downloads, playlists and settings.
- Preserve process ownership, cancellation, timeouts and bounded buffers.
- Treat requested hardware decoding and actual decoding as separate facts. Keep hardware failures visible in diagnostics.
- Do not claim performance gains without measurements. Assess media-time progress, wall time and A/V sync; dropped-frame counts alone do not establish smooth playback.
- Diagnostics must continue playback when process or resource counters are unavailable.
- Do not add continuous yt-dlp polling or unbounded background requests.
- Keep cookies, OAuth tokens, browser profiles and user data out of commits, uploads and diagnostic output.

## Build and validation

- Use `Build.ps1` for the main application and `Build-All.ps1` to build all four first-party executables together.
- Use `Run-Checks.ps1` for the current test harness. The `RUN_*TESTS.cmd` scripts are historical.
- Report compilation, offline checks, Windows runtime checks and real GPU validation separately.
- .NET 8 harness checks do not validate WinForms, D3D11VA, audio or graphics drivers.
- `tests/test_mpv_reuse.py` uses Unix IPC and is not a Windows hardware test.
- Do not run long benchmarks or modify installed tools/settings without explaining the intended operation.
