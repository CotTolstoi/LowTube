# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate (v1/v2 verified)
- Updated: 2026-11-19
- SHA-256: `F2AC243A80D0668786A3BD6510EFD5C0BA06BF18B053C551CC907BED74A5E3F6`
- Resolver: bounded direct-format resolution supports the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Runtime OAuth acceptance still requires the owner-registered Android OAuth client and enabled YouTube Data API.
- Diagnostics: Settings copies a sanitized report with app/system/device details, actual decoder status, playback buffer, last manual CPU/PSS/RSS/RAM sample, and aggregate download-job statuses. Custom jobs use SQL aggregation; legacy DownloadManager scanning is capped at 500 IDs and marks partial results. It excludes account data, video titles/IDs, URLs, paths, error reasons, search history, cookies and tokens.
- Validation: `:app:assembleDebug` passed; formatter unit tests passed 3/3; API 23 SQL aggregation and clipboard UI instrumentation tests passed 1/1 each; installed and launched on Redmi Note 8 / Android 13 API 33. Manifest minSdk is 23. Player code did not change, so playback was not retested.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
