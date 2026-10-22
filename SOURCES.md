# LowTube 0.6 — sources and third-party components

Checked 2026-10-09. Third-party tools are downloaded by the setup script, not bundled in this archive.

| Component | Source / license | Purpose |
|---|---|---|
| yt-dlp | https://github.com/yt-dlp/yt-dlp — Unlicense; bundled executable also includes dependencies with their licenses | Search, account feeds, media extraction, downloads |
| mpv | https://mpv.io/installation/ and https://github.com/mpv-player/mpv — GPL/LGPL depending on build | Playback and hardware decoding |
| Windows mpv builds | https://github.com/shinchiro/mpv-winbuild-cmake/releases | Baseline x86_64 player binaries |
| Windows FFmpeg / ffprobe builds | https://github.com/yt-dlp/FFmpeg-Builds/releases | Complete win64 shared package, including both executables and their DLLs |
| FFmpeg | https://ffmpeg.org/legal.html — LGPL/GPL depending on build | Stream merging, remuxing and audio conversion |
| QuickJS-NG | https://github.com/quickjs-ng/quickjs — MIT | JavaScript challenge runtime |
| Vulkan loader runtime | https://vulkan.lunarg.com/sdk/home#windows — see official runtime licenses | Mandatory startup DLL for the selected mpv build; GPU rendering still uses D3D11/OpenGL |
| 7-Zip | https://github.com/ip7z/7zip and https://www.7-zip.org/license.txt — see distribution license | Temporary extraction of .7z packages |

Setup records resolved release URLs and SHA256 digests in `tools/versions.json`.
The optional app-local Vulkan runtime uses the official 1.4.363.0 Components ZIP,
SHA256 `a25a927aa8b9f0371048f1861cf88ac3b9bc9b1fb332c42d897c8ab32695769a`.
An existing system x64 Vulkan loader can be used instead. The automatic LunarG
download could not be completed in the development environment (HTTP 403 / 1010);
setup provides an official manual-install route before downloading large components.
Do not redistribute third-party binaries without satisfying their licenses.
LowTube's own sources are provided under the MIT license in LICENSE.txt.

Technical references:

- mpv hardware decoding, DXVA2 copy modes, D3D11, input IPC and decoding diagnostics: https://mpv.io/manual/stable/
- Baseline versus AVX x86_64-v3: https://github.com/shinchiro/mpv-winbuild-cmake/wiki/Difference-between-x86_64-and-x86_64-v3
- yt-dlp formats, feeds, cookies, postprocessing: https://github.com/yt-dlp/yt-dlp
- Authentication limitations: https://github.com/yt-dlp/yt-dlp/wiki/Extractors
- QuickJS and embedded EJS support: https://github.com/yt-dlp/yt-dlp/wiki/EJS
- Server-side watched-state reliability is not assumed: https://github.com/yt-dlp/yt-dlp/issues/8236

The UI is an independent client; it is not endorsed by Google, YouTube, NVIDIA or the component maintainers.

0.3 feed implementation references (2026-10-10):
- Public channel Atom feed, directly requested without cookies:
  https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw
- yt-dlp channel/search metadata extraction, including channel_id:
  https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_tab.py
- Guest topic discovery uses the documented ytsearch100: search prefix.
  Local subscriptions do not use :ytsubs or a Google account's subscription data.

0.4 startup / player references (2026-10-10):
- Official unpackaged Windows x64 yt-dlp distribution and dependencies:
  https://github.com/yt-dlp/yt-dlp#release-files
- PyInstaller one-file extraction lifecycle:
  https://pyinstaller.org/en/stable/operating-mode.html#how-the-one-file-program-works
- mpv cache limits, startup/refill threshold, hr-seek and audio synchronization:
  https://mpv.io/manual/stable/
- mpv seek/playback-restart events, script-message and JSON IPC:
  https://github.com/mpv-player/mpv/blob/master/DOCS/man/input.rst
- The bundled lowtube.lua is first-party application code, covered by LICENSE.txt.
  Only that script is loaded explicitly; automatic external script loading stays off.
- JsonCodec.cs is first-party bounded JSON code. System.Web.Extensions is no longer
  a build/runtime reference. No third-party JSON package was added.

## 0.5 account and window integration
- Google OAuth desktop apps (PKCE, loopback, refresh, revoke): https://developers.google.com/identity/protocols/oauth2/native-app
- Google OAuth token expiry: https://developers.google.com/identity/protocols/oauth2
- Google subscriptions.list: https://developers.google.com/youtube/v3/docs/subscriptions/list
- YouTube API history/Home limitations: https://developers.google.com/youtube/v3/revision_history
- mpv ontop: https://mpv.io/manual/master/#options-ontop
No Google application identity or OAuth credentials are included in the build.
