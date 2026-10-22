# LowTube 0.7.0: Windows hardware decoding

| Mode | Intended use | Renderer |
|---|---|---|
| auto | Let mpv choose a supported, allowlisted hardware path; software fallback | selected |
| auto-copy | Automatically choose a copy-back path, useful with software video filters | selected |
| dxva2 | Legacy Windows GPUs, including a test candidate for legacy NVIDIA GPU | D3D11 selected automatically |
| dxva2-copy | Legacy GPUs; actual use on the user's legacy NVIDIA GPU was confirmed in logs | selected |
| d3d11va | Compatible newer Intel/AMD/NVIDIA and newer Atom GPUs | D3D11 selected automatically |
| d3d11va-copy | Same API with frames copied through system RAM | selected |
| nvdec | NVIDIA with sufficiently recent supported hardware/driver APIs; not a promise for legacy NVIDIA GPU | OpenGL selected automatically |
| nvdec-copy | NVIDIA hardware decoding with copy-back | selected |
| no | CPU decoding; useful when hardware is absent or its driver path performs poorly | selected |

DXVA2 direct may avoid copy-back overhead, but older drivers can have color/range
conversion bugs. Compare image colors and the actual `hwdec-current` value.
No mode makes unsupported codecs supported in hardware, and a selected mode
may fall back to software. Hardware support and rendering support are separate.
The legacy NVIDIA GPU is listed by NVIDIA as feature set B (H.264-era decode hardware).
The historical NVIDIA tables describe Linux hardware capability, not certification
of modern Windows mpv or the Windows driver. Its measured DXVA2-copy path in this
user's logs struggles at 1080p60, while 720p60 performs far better. Direct DXVA2
has not yet been tested on that laptop. NVDEC is provided for other NVIDIA users;
it is not being presented as a modern-driver upgrade for this legacy chip.

VAAPI/VDPAU (Linux paths), VideoToolbox (Apple), MediaCodec (Android), deprecated
CUDA variants and CrystalHD are not offered. QSV is not added as a standalone
mpv hwdec choice: supported Intel Windows GPUs use the D3D11VA/DXVA2 paths here.
Vulkan Video is not included because this UI uses `vo=gpu`, not `gpu-next`.
The included Vulkan loader satisfies the mpv binary dependency; Vulkan is not
selected as the rendering or decoding backend.

Lenovo S12 exists with different hardware. The user's older 32-bit Atom with Intel integrated graphics variant
is a 32-bit platform without full H.264 hardware decoding. This Windows x64 build
cannot run on it. A 32-bit app alone would also be insufficient: compatible
32-bit media tools and JS runtime integration would be needed. Newer 64-bit Atom
systems are a different case and depend on their actual GPU, codec and driver.

## Sources checked 2026-10-11
- https://mpv.io/manual/stable/#options-hwdec
- https://download.nvidia.com/XFree86/Linux-x86_64/290.10/README/supportedchips.html
- https://download.nvidia.com/XFree86/Linux-x86_64/290.10/README/vdpausupport.html
- https://community.intel.com/t5/Embedded-Intel-Atom-Processors/Performance-comparison-for-MPEG2-Atom-amp-GMA500-or-945GSE/m-p/244250
- https://www.intel.com/content/www/us/en/docs/onevpl/developer-reference-media-intel-hardware/1-1/overview.html

## Validation boundary
Compiled C#5/.NET Framework 4.8 x64. 163 core assertions, 19 Lua checks, localization
checks and offline installer tests passed. PE architecture and adjacent component
DLLs inspected. Full Windows UI/GPU playback and live YouTube searches/downloads
have not been run in this Linux build environment. No smooth 1080p60/4K guarantee.
