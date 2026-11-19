# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate
- Updated: 2026-11-19
- SHA-256: `1783F9D3665920452100F4BFF6B940341F7DC8D33ACA4FEBD283B22F3023851B`
- Resolver: bounded direct-format resolution supports the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Runtime OAuth acceptance still requires the owner-registered Android OAuth client and enabled YouTube Data API.
- Diagnostics: Settings can copy a sanitized report containing app/system details, actual decoder status, playback buffer and the last manual resource sample. It excludes account data, video titles/IDs, URLs, search history, cookies and tokens.
- Validation for this APK: `:app:assembleDebug` passed; formatter unit tests passed 2/2; API 23 clipboard UI acceptance passed 1/1; installed and launched on Redmi Note 8 / Android 13 API 33. v1/v2 APK signatures verified and manifest minSdk is 23. Player code did not change, so playback was not retested.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.