# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate
- Updated: 2026-11-19
- SHA-256: `101FD49740DA86BA6B377740E57EDC2F39B42D2BB57963D2430909AB84997AAD`
- Resolver update: playback keeps the iOS HLS fallback; downloads continue past HLS-only responses; audio-only downloads can use an Android VR direct-audio fallback after standard clients fail. The VR fallback is not used for video playback or video downloads.
- Validation: debug and AndroidTest APKs built; bounded audio Range acceptance passed on Android 6/API 23 AVD and Redmi Note 8/Android 13 API 33 (itag 251, HTTP 206, 65,536 bytes). No full download or playback was performed.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
