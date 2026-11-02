# LowTube — instructions for coding agents

Read README_CODEX_UK.md, handoff/PROJECT_STATE.md and handoff/ARCHITECTURE.md before changes.
This is an existing C# 5 / .NET Framework 4.8 Windows Forms application, not WPF, Electron or a new app.

## User requirements

- Communicate in Ukrainian. Preserve all existing features; never remove a feature silently.
- Discuss substantial functional/architectural changes before implementing them. The hybrid decoding experiment is already authorized; fixes and diagnostics within that experiment may proceed.
- Keep the stable application and the experimental hybrid pipeline separately launchable.
- Target Windows 10 x64 and very old x86_64 CPUs without AVX/AVX2 requirements. Do not fetch x86_64-v3 builds.
- Main weak test laptop: ASUS X71SL, Core 2 Duo T9600, GeForce 9300M GS, 4 GB DDR2.
- Future Windows 7 32-bit / old Linux / Android ports are backlog, not already supported targets.
- English is default UI; preserve English/Ukrainian/Russian catalogs and theme settings.
- User normally wants only changed files for patches. This archive is an explicitly requested complete project transfer.
- Saved browser session import is local. Never commit, upload or print real cookies, OAuth tokens, browser profiles or user data.

## Implementation boundaries

- Build via Build.ps1 / Build-All.ps1. Keep C# 5 compatibility (no modern syntax or new runtime dependencies without discussion).
- Maintain mpv reuse, IPC, resume position, queue, equalizer, downloads, playlists and existing settings.
- Preserve ProcessJob child-process ownership, cancellation, timeouts and bounded buffers.
- Never treat requested hardware decoding as verified actual decoding. Hardware failures must remain visible in diagnostics.
- Do not claim performance gains without measurements. Dropped frames alone do not establish smooth playback; assess media-time advance vs wall time and A/V sync.
- Diagnostics must not terminate playback when process/resource counters are unavailable.
- Do not add continuous yt-dlp polling or unbounded background requests.

## Validation

- Build all four first-party executables together to avoid runtime method-signature mismatches.
- Run relevant tests; separate compilation, offline checks, Windows runtime checks and real GPU validation in your report.
- Local .NET 8 harness tests do not validate Windows Forms, D3D11VA, audio or old GPU drivers.
- The scripts RUN_*TESTS.cmd are historical; Run-Checks.ps1 builds tests against the current app assembly.
- test_mpv_reuse.py uses Unix IPC and cannot be presented as a Windows hardware test.
- Never run long benchmarks or modify the user's installed tools/settings without explaining the intended action.
- Keep historical checksum files for context; handoff/TRANSFER_SHA256.json identifies this snapshot.
