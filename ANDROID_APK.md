# LowTube Android APK

This branch publishes the latest available Android development build.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate (v1/v2 verified)
- Updated: 2026-11-19
- Source revision: `8c055ab`
- SHA-256: `7A5A44D667B5BA3EA15BE85AA5D691E557C9D0F86304BD1AC4BF1BCDB7B5249A`
- Resolver: bounded direct-format resolution supports verified Android/WEB/iOS/MWEB fallback and the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. Runtime OAuth acceptance still requires the owner-registered Android OAuth client and enabled YouTube Data API.
- Diagnostics: Settings copies a sanitized report with app/system/device details, actual decoder status, playback buffer, last manual CPU/PSS/RSS/RAM sample, aggregate download-job statuses and the last Media3 error code. For HTTP errors it includes only a numeric status. Exception messages, request URLs, media metadata, account data, search history, cookies and tokens are excluded. Custom jobs use SQL aggregation; legacy DownloadManager scanning is capped at 500 IDs and marks partial results.
- Downloads UI: incomplete jobs retain percentage and byte progress while paused, failed or cancelled; small files show B/KiB. Paused jobs offer Retry and a correctly labeled Remove action. Empty state and Back action are covered. The cached subscriptions list now renders all stored items (up to the repository bound of 3,000), without the former UI cap of 30. Separate AVC/AAC MP4 tracks can now be selected and are muxed by Android without transcoding.
- Page size: Settings supports a persistent visible result page size from 1 to 250 (default 25); the value is included in the allowlisted PC settings JSON transfer. Search and For You reveal loaded results in this many-item increment. YouTube still controls server continuation page sizes; live scrolling through multiple continuation pages remains open for acceptance.
- Navigation: the four primary destinations use tintable Android vector icons for Search, For you, Subscriptions and Profile; no extra icon library was added.
- Validation: `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed; API23 `MainScreenTest#primaryTabsAndMoreMenuRemainReachableOnApi23` passed 1/1 after the navigation-icon change. API23 Settings and settings-transfer instrumentation passed 11/11; API23/API33 local synthetic mux acceptance passed. Locale parity check passed (267 keys, no missing/extra entries). The production MWEB signature resolver returned bounded HTTP 206 / 65,536-byte CDN ranges for two public fixtures on API23; this is resolver/download validation, not playback. On the physical Android13 device, the isolated download service resumed separate video/audio HTTP Range offsets in bounded 8 KiB chunks, muxed both tracks and published a two-track MP4 (1/1). Page-size setting and updated navigation icons were checked on the device. MainActivity was left foreground. No playback was run for this UI change. Manifest minSdk is 23.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.
