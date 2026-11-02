# Transfer validation — 2026-10-15

Performed on the staged handoff copy, not on the user's installed application.

- Compiled LowTube.exe, LowTube.Benchmark.exe, LowTube.Hybrid.exe and LowTube.Hybrid.Compare.exe using Roslyn with /langversion:5, /platform:x64 and .NET Framework 4.8 reference assemblies.
- Compiled CoreTests, FeatureTests, GoogleTests, ContinuityTests and BenchPlanTests against the newly built application/tester assemblies. This verifies current API signatures; these Windows-target executables were NOT executed on Linux.
- Localization audit: 486 complete EN/UK/RU entries; 391 referenced translation keys; matching placeholders; passed.
- Lua benchmark sampler: six checks for lifecycle/readiness/actual decoder/privacy/IPC/EOF; passed with the existing local Lupa dependency.
- Hybrid queue harness: 30,000 ordered frames, fixed pool reuse, cancellation/startup errors and unavailable process counters; passed on .NET 8.
- Hybrid comparison harness: parser/classification, media vs wall timing, hardware verification, tail exclusion, stage accumulation and control throughput; passed on .NET 8.
- Python response-file paths in hybrid harnesses quoted for SDK/workspace paths containing spaces. Retested after this portability adjustment.
- Main application/player/benchmark/hybrid runtime source files otherwise preserved byte-for-byte from the working project snapshot. Only transfer docs, build/test entry points, editor tasks and test harness quoting were added/adjusted in this copy.
- External tool binaries excluded; captured inventory and hashes retained.
- Reports copied with user-home directory labels and potential signed-media URLs/authorization lines redacted. Original uploads were not modified.
- No local settings, browser profiles, real cookies, OAuth client credentials or tokens included. Test fixtures and parsing code naturally contain credential-related identifiers/dummy values.

NOT performed here: Windows PowerShell execution of Build-All.ps1/Run-Checks.ps1; Windows Forms UI launch; audio output; D3D11VA on NVIDIA 9300M GS; a full 0.5 performance run; browser login/session import. Compilation/offline results do not establish these behaviors.

Historical SHA256*.json / PATCH*_SHA256.json files refer to their historical packages and may not match a rebuilt snapshot. TRANSFER_SHA256.json is the authoritative transfer content inventory and intentionally excludes itself.
