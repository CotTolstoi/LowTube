# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate
- Updated: 2026-11-19
- SHA-256: `8A2E1C8E33F8C9A762A0A4B78C591042C50C17455319984A3DDB3170A77FC25F`
- Resolver update: playback keeps the iOS HLS fallback and now deciphers a verified MWEB signature-helper path; audio-only downloads can use the Android VR direct-audio fallback after standard clients fail. The VR fallback is not used for video playback or video downloads.
- Validation: debug and AndroidTest APKs built; MWEB itag 18 playback acceptance passed on Android 6/API 23 AVD and Redmi Note 8/Android 13 API 33, reaching about 2 seconds with a 12-second clip limit; CDN returned HTTP 206 for a 65,536-byte Range. Audio-only itag 251 Range acceptance also passed on both devices. v1/v2 debug APK signatures verified. No full video or audio file was played/downloaded.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
