# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate (v1/v2 verified)
- Updated: 2026-11-19
- SHA-256: `8681608FCE5935E7795FC2C766517E128D87C37BFC276D715D2DA4D558E83D07`
- Resolver: bounded direct-format resolution supports the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Runtime OAuth acceptance still requires the owner-registered Android OAuth client and enabled YouTube Data API.
- Diagnostics: Settings copies a sanitized report with app/system/device details, actual decoder status, playback buffer, last manual CPU/PSS/RSS/RAM sample, aggregate download-job statuses and the last Media3 error code. For HTTP errors it includes only a numeric status. Exception messages, request URLs, media metadata, account data, search history, cookies and tokens are excluded. Custom jobs use SQL aggregation; legacy DownloadManager scanning is capped at 500 IDs and marks partial results.
- Validation: `:app:assembleDebug` passed; diagnostic formatter unit tests passed 4/4; API 23 SQL aggregation and clipboard UI instrumentation tests passed 1/1 each; installed on the API23 AVD and connected Android 13/API 33 device, with MainActivity foreground. Manifest minSdk is 23. Player behavior did not change, so playback was not retested.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
