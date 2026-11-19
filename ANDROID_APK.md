# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate
- Updated: 2026-11-19
- SHA-256: `1519B684DE7995B96643502303599430039118FD635D0C40A2BDE4A8C42D7476`
- Resolver update: playback and direct video downloads can resolve the bounded MWEB signature-helper path; audio-only downloads retain the Android VR direct-audio fallback. The VR fallback is not used for video playback or video downloads.
- Account update: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Web client ID and Credential Manager dependencies were removed. Google Cloud Android OAuth registration is still required; live sign-in was not verified because owner configuration is unavailable.
- Validation for this APK: `:app:assembleDebug` passed; APK v1/v2 signatures verified; manifest minimum SDK is 23. Player code did not change, so playback was not retested for this account-flow update. Earlier bounded playback/download acceptance remains documented in the project roadmap.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.