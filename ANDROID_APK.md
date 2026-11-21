## Current artifact (2026-11-21, source revision 122cc4b)

- File: `LowTube-Android-debug-0.1.15.apk`; version `0.1.15-android-dev` (version code 16); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `122cc4b` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.15: an already prepared queue item survives leaving the Player route; its queue-row identity lets the persistent session observer consume the row and refresh the mini-player after transition.
- Bounded playback acceptance passed on Android 6/API 23 AVD: a 12 s first clip and about 1 s of the next item, always below 120 s. Leaving Player and background UI synchronization were not acceptance-tested. APK installed on Xiaomi Pad 6; MainActivity verified foreground. No playback started on the tablet.
- APK size: 24773304 bytes.
- SHA-256: `05510AD2057254E0CC6526E4A7E1348F327B5539E30E4FA058E69B43C2D52DAB`.

## Current artifact (2026-11-21, source revision 2389777)

- File: `LowTube-Android-debug-0.1.14.apk`; version `0.1.14-android-dev` (version code 15); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `2389777` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.14: local channel subscriptions can be removed from the Subscriptions filter after confirmation; this does not change a Google account subscription.
- Playback was not run; playback logic was unchanged. APK installed on Xiaomi Pad 6 and MainActivity verified foreground. The local removal UI was not acceptance-tested.
- APK size: 24595972 bytes.
- SHA-256: `9CE72FB4F221CE4AC4980A9C704DC3E6E0460E8CB8DC3D1D298248821516ADC2`.

## Current artifact (2026-11-21, source revision 4fddd4a)

- File: `LowTube-Android-debug-0.1.13.apk`; version `0.1.13-android-dev` (version code 14); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `4fddd4a` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.13: the mini-player displays playback progress, refreshed every 500 ms during playback.
- Playback was not run; playback logic was unchanged. APK installed on Xiaomi Pad 6 and MainActivity verified foreground. UI acceptance was not run.
- APK size: 24593479 bytes.
- SHA-256: `9832001F4E80AADE3F3A1C39D57B11185D5EB9EFFE6E2FF3519278E225E3E578`.

## Current artifact (2026-11-21, source revision 672a592)

- File: `LowTube-Android-debug-0.1.12.apk`; version `0.1.12-android-dev` (version code 13); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `672a592` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.12: Google authorization failures now show a safe status name/code and provider/version, including ApiException values found in the bounded cause chain; no account data or token is shown. On Xiaomi Pad 6, two first-account attempts returned status 8 (`INTERNAL_ERROR`) from Google Play services 26.45.31; sign-in remains unaccepted.
- Playback was not run; player code was unchanged. APK installed on Xiaomi Pad 6 and MainActivity verified foreground.
- APK size: 24593478 bytes.
- SHA-256: `E3689CB9E38D55581906060E96716B6C95B89CF8F821B4E47A892E500522E04D`.

## Current artifact (2026-11-21, source revision b4edbb4)

- File: `LowTube-Android-debug-0.1.10.apk`; version `0.1.10-android-dev` (version code 11); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `b4edbb4` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.10: Subscriptions channel filter chips display cached channel avatars with an initial-letter fallback; no additional network request is made.
- Playback was not run; player code was unchanged. APK installed on Xiaomi Pad 6 and MainActivity verified foreground. UI acceptance was not run.
- APK size: 24592429 bytes.
- SHA-256: `68F91DC41362E44F7EA1FAEF81CB86A3A564CC5DE2249BE649A0D6EF740D902C`.

## Current artifact (2026-11-21, source revision b8e77c1)

- File: `LowTube-Android-debug-0.1.9.apk`; version `0.1.9-android-dev` (version code 10); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `b8e77c1` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.9: local library backups now preserve ordered queue items and playlist continuation markers; importing older backups without `Queue` leaves the existing queue unchanged. Import confirmation is localized in EN/UK/RU.
- Playback was not run; player code was unchanged. APK installed on Xiaomi Pad 6 and MainActivity verified foreground; library import UI was not manually exercised.
- APK size: 25035301 bytes.
- SHA-256: `C2C754D0E4F07046E2D985F3CD4E8C9F2ACB247F548D82979C64F6790EA93161`.

## Current artifact (2026-11-21, source revision b16808a)

- File: `LowTube-Android-debug-0.1.8.apk`; version `0.1.8-android-dev` (version code 9); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `b16808a` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.8: playback settings now offer system default, hardware preferred and software preferred video decoder modes; the selected preference is applied when PlaybackService next starts, transferred through PC JSON `Decoder`, and reported separately from the actual decoder.
- Bounded API23 software-preferred playback acceptance passed: `OMX.google.h264.decoder`; 12-second first clip plus 1.095 seconds of the next item. No video was allowed to finish.
- APK size: 24575848 bytes.
- SHA-256: `9AD71DB281EBE145DE574EDAA8F5CB2F31719E8C5327AD81B2D264633EE9E8DE`.
## Current artifact (2026-11-21, source revision 45b0376)

- File: `LowTube-Android-debug-0.1.7.apk`; version `0.1.7-android-dev` (version code 8); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not production-signed.
- Source revision: `45b0376` on `codex/android-shorts-continuation`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.7: download retries now run in a ViewModel and keep retry state across Activity recreation.
- APK size: 24556154 bytes.
- SHA-256: `6717A77DB582F67F95454410B9370BAAF6AC202BACB911E1514D48B4C67F0E2D`.
- Playback was not run for this Downloads UI update.
## Current artifact (2026-11-21, source revision 77b8ec0)

- File: LowTube-Android-debug-0.1.6.apk; version 0.1.6-android-dev (version code 7); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with Android debug certificate; not production-signed.
- Source revision: $src on codex/android-shorts-continuation.
- Build: :app:assembleDebug --offline --no-configuration-cache succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Change in 0.1.6: canceled downloads now display a distinct Cancelled status.
- APK size: 24556151 bytes.
- SHA-256: $h.
- Playback was not run for this Downloads UI update.
## Current artifact (2026-11-21, source revision 6746154)

- File: LowTube-Android-debug-0.1.5.apk; version 0.1.5-android-dev (version code 6); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with Android debug certificate; not production-signed.
- Source revision: $source on codex/android-shorts-continuation.
- Build: :app:assembleDebug --offline --no-configuration-cache succeeded for armeabi-v7a, arm64-v8a and x86_64.
- Changes in 0.1.5: subscription feed refresh notifies the foreground UI after each channel feed is saved, including when a later refresh is cancelled. Feed update notifications use a state stream so an update is not lost before the screen subscribes.
- APK size: 24556151 bytes.
- SHA-256: $hash.
- Playback was not run for this UI/feed update.
## Current artifact (2026-11-20, source revision a4d0484)

- File: `LowTube-Android-debug-0.1.4.apk`; version `0.1.4-android-dev` (version code 5); minimum Android 6.0 (API 23).
- Build type: installable debug APK signed with the Android debug certificate; not a production-signed release.
- Source revision: `a4d0484` on local task branch `codex/android-resolver-signature-fallback`.
- Build: `:app:assembleDebug --offline --no-configuration-cache` succeeded for armeabi-v7a, arm64-v8a, and x86_64. The signature-fallback code in this APK was playback-accepted on API23 and API33 AVDs immediately before the version-only bump: each run used a 12-second clipped first item and about one second of the next item, remaining below two minutes. This covers one public fixture and is not broad extractor compatibility proof.
- Localized voice search uses the Android system RecognizerIntent; API33 AVD has a handler, while the default API23 AVD has none and displays the localized unavailable message. UI runtime acceptance was not run.
- The APK was not installed on the physical Android device; it is currently absent from ADB. No playback or download was started during the final 0.1.4 build.
- APK size: 24503692 bytes.
- SHA-256: `CD16120905384097C6740E29DD08CD3834E1048F534EC864E34364C6FA64C0CC`.
## Previous artifact details (source revision 52c984e; historical)

- Built from Android development branch `feat/android-first-run-setup` at source commit `52c984e`; the APK includes the SAF download-folder option in first-run setup and centralized persistable-grant cleanup. Unit and instrumentation test sources are not packaged in the APK.
- `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded. `FirstRunSetupScreenTest` passed 3/3 on API23. Manual API23 SAF acceptance selected the Download folder, showed its name in setup, then used Use default; no video or download was started for this publication.
- A live player-bundle diagnostic rewrote only the `/n/{segment}` path challenge in a synthetic URL; trusted origin, unchanged query, and the production safety gate all passed. `YoutubeResolverPlaybackAcceptanceTest#resolvedStreamStartsAndAdvancesWithoutReachingTwoMinutes` passed 1/1 on the API23 AVD and again on the connected physical Android13/API33 ARM64 device using public video `aqz-KE-bpKQ`. Each run clipped the first item at 12 seconds and advanced to the next item for about 1 second. API23 decoder: `OMX.google.h264.decoder`, hardware status unknown; API33: `c2.mtk.avc.decoder`, `avc1.4D401E`, hardware acceleration status 1. The connected system reports `TrebleDroid vanilla`; Redmi Note 8 identity is unverified. These runs confirm one resolver fixture, not broad YouTube compatibility. `DecoderBenchmarkEngineAcceptanceTest#decoderProfilesRunAgainstTheSameBoundedLocalVideo` also passed 1/1 on API23 and 1/1 on the connected API33 device using an isolated synthetic AVC file and a 10-second media cap per profile. API23 selected `OMX.google.h264.decoder` for DEFAULT and SOFTWARE_PREFERRED; API33 selected hardware `c2.mtk.avc.decoder` for DEFAULT/HARDWARE_ONLY and software `c2.android.avc.decoder` for SOFTWARE_PREFERRED. The API33 engine reported SLOW_OR_STALLED for all profiles at a media/wall ratio below 0.95; this single synthetic run does not establish a profile performance advantage. The API23 Compose benchmark screen test passed; manual More → Decoder benchmark → DocumentsUI source selection returned to the screen. TXT export with a real benchmark result remains open; no additional decoder run was performed.
- This is an installable debug APK signed with the debug certificate, not a production/release-signed package.

- File: `LowTube-Android-debug-0.1.0.apk`
- Version: `0.1.0-android-dev` (version code 1)
- Minimum Android version: Android 6.0 (API 23)
- Build type: debug; signed with the Android debug certificate (v1/v2 verified)
- Build tools: AGP 9.2.0, Gradle 9.4.1; Kotlin metadata rewrite warning resolved
- Updated: 2026-11-19
- Build source revision: `52c984e` (`feat/android-first-run-setup`).
- APK size: 25,751,571 bytes
- SHA-256: `5F83FDDFBFB91B70FD213179DBC6EB2A932372465EDC36D00C7A048EA06D60B7`
- Latest build: `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded for source revision `52c984e`. The connected Android13/API33 system installed and launched it with MainActivity top-resumed. Five-second idle sample: CPU 0%, PSS 112.8 MiB, RSS 186.2 MiB, peak RSS 186.3 MiB, RAM 3,713/1,573 MiB. This is one point sample, not a benchmark; the system reports TrebleDroid vanilla.
- Recent Android changes: tablet-aware Library/Settings layouts; first-run language, UI scale, theme, quality, audio bitrate, and SAF download-folder setup; LAME MP3 conversion; in-app playback of completed audio downloads; sequential decoder-profile comparison. Engine-level local comparison acceptance passed on API23 and API33; manual benchmark-screen, SAF-source, and export acceptance remains open.
- Resolver: bounded direct-format resolution supports verified Android/WEB/iOS/MWEB fallback and the verified MWEB signature-helper path; audio-only downloads retain the Android VR fallback. The VR fallback is not used for video playback or video downloads.
- Account sign-in diagnostics: account-picker dismissal and Google OAuth authorization dismissal have distinct localized messages; canceled authorization intents are parsed for Google API errors before being classified as user cancellations, and the localized guidance points to package/SHA-1 OAuth client configuration. Runtime OAuth acceptance still requires the owner-registered Android OAuth client, enabled YouTube Data API, and user consent.
- Account: Profile uses the system Google account picker, Google AuthorizationClient and a bounded OpenID UserInfo request. YouTube subscription changes use subscriptions.insert/delete and request the `youtube` account-management scope only when the signed-in user taps Subscribe/Unsubscribe; access tokens are not persisted. Runtime OAuth acceptance still requires the owner-registered Android OAuth client, enabled YouTube Data API, and user consent.
- Account profile: the OpenID picture URL is accepted only from HTTPS `lh3.googleusercontent.com` or `lh4.googleusercontent.com`, bounded to 2,048 characters, rendered with an initial fallback and removed from local preferences on sign-out. The build was installed/launched, but real account/photo rendering still awaits the owner OAuth client and consent.
- Account playlists: `[Private video]`, `[Deleted video]`, and malformed/missing snippet entries are retained as visible unavailable rows so remaining playlist items do not shift. Unavailable rows have no Play action and are filtered from Play all/Add to queue. The runtime account flow is still unverified without the owner OAuth client and consent.
- Public playlists: InnerTube playlist renderers preserve valid-ID private/deleted/unplayable rows as unavailable cards with localized status and no play/queue/download/save actions. Queue-page expansion filters these entries while preserving available-item order and continuation. Runtime acceptance for these cases remains open.
- Download notifications: title/channel, preparing, paused/resume, complete, failed, cancelled and Cancel action use EN/UK/RU resources. Failure notifications omit raw exception text; details remain in Downloads. The real foreground preparation notification passes EN/UK/RU acceptance on API23/API33, including ongoing/indeterminate state and no premature job actions; actual paused paths pass all three locales on both API levels. Payload/action and failure/publication paths have their separately bounded acceptance results in the Android roadmap.
- Diagnostics: Settings copies a sanitized report with app/system/device details, actual decoder status, playback buffer, last manual CPU/PSS/RSS/RAM sample, aggregate download-job statuses and the last Media3 error code. For HTTP errors it includes only a numeric status. Exception messages, request URLs, media metadata, account data, search history, cookies and tokens are excluded. Custom jobs use SQL aggregation; legacy DownloadManager scanning is capped at 500 IDs and marks partial results.
- Downloads UI: incomplete jobs retain percentage and byte progress while paused, failed or cancelled; small files show B/KiB. Paused jobs offer Retry and a correctly labeled Remove action. Empty state and Back action are covered. The cached subscriptions list now renders all stored items (up to the repository bound of 3,000), without the former UI cap of 30. Separate AVC/AAC MP4 tracks can now be selected and are muxed by Android without transcoding.
- Page size: Settings supports a persistent visible result page size from 1 to 250 (default 25); the value is included in the allowlisted PC settings JSON transfer. Search and For You reveal loaded results in this many-item increment. YouTube still controls server continuation page sizes; live scrolling through multiple continuation pages remains open for acceptance.
- Local playlists: create, rename, delete, save search results, reorder items and remove items are implemented. The UI reorder/remove test passed on API23 and API33 with isolated SQLite data; actions have localized accessibility labels. No video playback was started for this acceptance.
- For You filter: the selected All/Videos/Shorts/Live filter is stored locally and restored across app/process restarts; unknown stored values fall back to All. It is a local presentation preference and does not change network queries or account data. Build/install/launch succeeded; explicit filter persistence UI acceptance remains open.
- Audio download preference: Settings stores preferred direct source bitrate 96/128/192/256/320 kbps and includes PC JSON `AudioBitrate`; audio-only source options are sorted by nearest available bitrate. MP3 export decodes supported audio to PCM through MediaCodec and encodes with the bundled LAME library using the selected target bitrate. Direct audio downloads still retain their original container/codec. Full live YouTube download compatibility and every codec/container remain unverified.
- Playback session: the last YouTube video, queue-row ID, selected SAF audio track, downloaded local video URI, or downloaded audio URI is checkpointed with bounded metadata. SAF audio requires its persisted read grant; downloaded audio restores only while the content URI remains readable. Cold launch restores the mini-player without autoplay; resume time comes from local watch history. Signed stream URLs and account credentials are not stored. Process-death restoration acceptance remains open.
- Localization: the discovery language fallback now resolves through the EN/UK/RU catalogs; the wider visible-string audit is still in progress.
- Navigation: the four primary destinations use tintable Android vector icons for Search, For you, Subscriptions and Profile; no extra icon library was added.
- Missing thumbnails: if a YouTube video/Shorts/live renderer omits its thumbnail, the UI builds the standard `i.ytimg.com` URL only from a validated 11-character video ID. Playlist and malformed IDs are excluded.
- Decoder benchmark: More contains local-file System default, Software preferred and verified Hardware only profiles (API29+; unavailable when the selected file has no hardware video decoder). Each run is clipped to at most 120 seconds of media time and reports actual decoder/format, process CPU as percentage of one core, PSS/RSS, available RAM, processed/dropped frames, audio underruns, frame-processing offsets and media-time versus wall-time. Compare runs supported profiles sequentially against the same local file, with each pass starting at media time 0. CPU is not divided by core count, and multicore process usage may exceed 100%. Export excludes source URI/name. Raw A/V sync is not exposed by this Media3 path. The screen and comparison action compile; comparison-profile playback acceptance remains open and was not launched for this build.
- Channel avatars: Search/For You cards load channel images only from YouTube's `yt3.ggpht.com` and `yt3.googleusercontent.com`; if the renderer omits an avatar or image loading fails, the channel initial remains visible. Avatar URLs persist across cached subscription uploads, local playlists, queue entries and watch history. Additive SQLite migrations v8→v9 and v9→v10 default old rows to blank. Physical Android13 screenshot confirmed a channel avatar rendered; API23/API33 AVDs and Redmi launched after schema updates.
- Watch history: entries now show YouTube thumbnails, channel/title, watched percentage/status and a compact progress bar; completed entries show a full bar. The playback implementation is unchanged. This UI change compiled and installed/launched on the connected API33 Android; screen-specific visual acceptance remains open.
- Validation: `:app:testDebugUnitTest` passed (145 tests, 0 failures); `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded. API23 `MainScreenTest#primaryTabsAndMoreMenuRemainReachableOnApi23` previously passed 1/1 after the navigation-icon change. API23 Settings/transfer instrumentation passed 11/11; API23/API33 synthetic mux acceptance passed. Locale parity check passed (267 keys, no missing/extra entries). The production MWEB signature resolver returned bounded HTTP 206 / 65,536-byte CDN ranges for two public fixtures on API23; this is resolver/download validation, not playback. On the physical Android13 device, Shorts with missing renderer thumbnail data displayed the fallback image. The isolated download service also resumed separate video/audio HTTP Range offsets in bounded 8 KiB chunks, muxed both tracks and published a two-track MP4 (1/1). LowTube remained foreground. Shorts playback and upward swipe from the first loaded Short to the next were accepted on API23 and API33; the API23 clip was paused at about 4.7 s, within the user cap of two minutes. API23 footer safe-area layout was corrected and visually reviewed. Save showed confirmation, Share opened the system chooser, and the local follow button updated immediately between Subscribe/Subscribed; remote YouTube subscription still awaits OAuth acceptance. API23 playlist URL → Queue acceptance also confirmed the parsed title `Uploads from NASA Video`. The dark palette now uses YouTube-like near-black surfaces and a LowTube red accent; the selected bottom-navigation pill was removed and the result was visually reviewed on the connected Android device. Library now contains an explicit owner-playlist loader and authenticated playlist-item detail reader using the existing `youtube.readonly` scope; results and tokens remain in memory. The API path compiled but could not be runtime-accepted because the owner OAuth client/Data API configuration is not available. The connected physical device reports TrebleDroid vanilla, not verified Redmi Note 8 hardware. After install/start, process sample PSS 124.95 MiB, RSS 220.12 MiB, swap PSS 0.46 MiB; point sample, not benchmark. The current UI install was sampled at CPU 0%, PSS 115.6 MiB, RSS 212.5 MiB and swap PSS 0.43 MiB; settled idle point sample, not benchmark. Manifest minSdk is 23.

- Previous build check (2026-11-19): `:app:assembleDebug :app:assembleDebugAndroidTest --offline` succeeded; `LocalPlaylistPlaybackActionTest#reordersAndRemovesPlaylistItemsThroughLibraryUi` passed 1/1 on API23 and 1/1 on API33. On the connected Android system, LowTube was left top-resumed; a 10-second post-launch sample was CPU 0.0%, PSS 140.6 MiB, RSS 238.5 MiB, swap PSS 0.46 MiB, device RAM 3,802.5/1,456.0 MiB total/available. This is one point sample, not a benchmark. Device reports `TrebleDroid vanilla`.

Post-install idle point sample on the connected Android, 2026-11-19 10:53 UTC: CPU 0.2% of one core, PSS 141.4 MiB, RSS 224.8 MiB, peak RSS 227.2 MiB, swap PSS 0.43 MiB, total/available system RAM 3,713/1,418 MiB. This is a single 10-second sample, not a benchmark. LowTube MainActivity is foreground. The system reports TrebleDroid vanilla, not verified Redmi Note 8 hardware.

This is for development and direct testing. It is not a release-signed APK for Google Play distribution.

- Shorts player: videos classified as Shorts open an immersive portrait player with Back, Subscribe, Watch later save and Share actions; labels use EN/UK/RU strings. Vertical swipes move within the already-loaded Shorts results and store current watch progress; they do not start a new resolver/search request. The regular playback screen remains unchanged. API33 render and first-to-second swipe acceptance passed; API23 install/launch passed, but Shorts UI acceptance and Save/Share/Subscribe taps remain open.

Previous build check (2026-11-19): source revision `13d9ef4`; `:app:assembleDebug --offline --no-configuration-cache` succeeded. The APK was installed on the connected Android13/API33 system and `MainActivity` is top-resumed. Cancellation from `ApiException.CANCELED` now shows provider-aware OAuth diagnostics; microG 0.3.18 is detected, but no sign-in retry or OAuth acceptance was run. Ten-second foreground sample: CPU 0%, PSS 112.4 MiB, RSS 200.1 MiB, peak RSS 227.7 MiB, device RAM 3,713/1,436 MiB total/available. No playback or download was started; this is one point sample, not a benchmark.

Latest build check (2026-11-19): source revision `c379013`; `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded. Library channel cards provide Videos/Shorts/Live/Playlists browse callbacks and preserve the selected route into Search. `LocalPlaylistPlaybackActionTest#localSubscriptionCanOpenVideosShortsStreamsAndPlaylists` passed 1/1 on API23 and 1/1 on API33 AVD using an isolated SQLite fixture; it made no live channel request or playback. The APK is installed on the connected Android13/API33 system and `MainActivity` is top-resumed. Ten-second sample: CPU 0%, PSS 118.8 MiB, RSS 207.5 MiB, peak RSS 230.6 MiB, device RAM 3,713/1,444 MiB total/available. Point sample, not a benchmark; the device identifies as TrebleDroid vanilla.


Latest build check (2026-11-19): source revision `e0754fc`; `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded. `ChannelHeaderParsingTest` and `ChannelHeaderCardTest` each passed 1/1 on API23 and API33 AVD. The channel browse result now carries a bounded header model and renders banner/avatar, title, subscriber text, description, and Subscribe action. The updated APK was installed on the connected Android13/API33 system; `MainActivity` is top-resumed. Ten-second foreground Home sample: CPU 0.4%, PSS 134.1 MiB, RSS 221.2 MiB, peak RSS 223 MiB, device RAM 3,713/1,446 MiB total/available. No playback or download was started. Single point sample, not a benchmark; system identifies as TrebleDroid vanilla. APK SHA-256: `D50C5CCAFFA145856DA8127F22B2B34854CB306772D526F8E3AA95D7ACC481A4`.
Latest build check (2026-11-19): source revision `6a416e4`; `:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache` succeeded. `ChannelHeaderParsingTest`, `ChannelHeaderCardTest`, and opt-in live `ChannelBrowseHeaderAcceptanceTest` passed 4/4 on API23 and 4/4 on API33. The real `https://www.youtube.com/@NASA/videos` response produced the current `pageHeaderViewModel`, a channel ID, subscriber count, avatar, banner, description, and 30 video cards. On the connected Android13/API33 system, LowTube displayed the NASA header and cards; the installed APK hash matches this artifact, `MainActivity` remains top-resumed, and playback/download were not started. Ten-second channel-page resource sample: CPU 0%, PSS 124.1 MiB, RSS 211 MiB, peak RSS 229.7 MiB, system RAM 3,713/1,429 MiB total/available. This is one point sample, not a benchmark; the system reports TrebleDroid vanilla. APK SHA-256: `D50C5CCAFFA145856DA8127F22B2B34854CB306772D526F8E3AA95D7ACC481A4`.


