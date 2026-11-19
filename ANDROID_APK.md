# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate
- Updated: 2026-11-19
- SHA-256: `22B8B3D40040444D30713268C60A2854DA74E206B2FD841581D8BF8EACF123B5`
- Resolver update: playback and direct video downloads can now resolve a verified MWEB signature-helper path when standard clients only provide `signatureCipher`; audio-only downloads retain their Android VR direct-audio fallback. The VR fallback is not used for video playback or video downloads.
- Validation: debug and AndroidTest APKs built; signature playback acceptance passed on Android 6/API 23 AVD and Redmi Note 8/Android 13 API 33, reaching about 2 seconds with a 12-second clip limit. Video download resolver acceptance passed on both devices with itag 18, HTTP 206, 65,536 bytes; audio-only itag 251 Range acceptance also passed on both. v1/v2 debug APK signatures verified. No full video or media file was played/downloaded.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
