# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate (v1/v2 verified)
- Updated: 2026-11-19
- SHA-256: `180074089EB3F773DA8DD0EC7382C2E59D88C9E4D0752E9E332B08CEA4005946`
- Resolver: bounded direct-format resolution supports verified Android/WEB/iOS/MWEB fallback and the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Runtime OAuth acceptance still requires the owner-registered Android OAuth client and enabled YouTube Data API.
- Diagnostics: Settings copies a sanitized report with app/system/device details, actual decoder status, playback buffer, last manual CPU/PSS/RSS/RAM sample, aggregate download-job statuses and the last Media3 error code. For HTTP errors it includes only a numeric status. Exception messages, request URLs, media metadata, account data, search history, cookies and tokens are excluded. Custom jobs use SQL aggregation; legacy DownloadManager scanning is capped at 500 IDs and marks partial results.
- Downloads UI: incomplete jobs retain their percentage and byte progress when paused, failed or cancelled; small files show B/KiB. Retry remains available for paused jobs.
- Validation: `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed. Diagnostic formatter unit tests passed 4/4. API 23 and API 33 instrumentation passed the rate-cap/resume acceptance (1/1 each), the paused-row UI test (1/1 each), and the external force-stop recovery test (1/1 each). Force-stop recovery resumed an 8-byte partial via `Range: bytes=8-23`, appended exactly 16 bounded bytes and cleaned the fixture. Installed on API23 AVD and connected Android13/API33 device; MainActivity was left foreground. Manifest minSdk is 23. Playback was not exercised during these download/UI checks.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
