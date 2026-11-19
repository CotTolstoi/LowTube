package com.lowtube.android

import android.widget.Toast
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.SideEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.lowtube.android.ui.main.LowTubeSearchScreen
import com.lowtube.android.ui.settings.SettingsScreen
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.data.PlaybackBufferPreferences
import com.lowtube.android.data.PlaybackSessionStore
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.SubscriptionFeedRefreshScheduler
import com.lowtube.android.downloads.DownloadDestination
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.QueueEntry
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.model.LocalAudioTrack
import com.lowtube.android.model.LocalVideoSource
import com.lowtube.android.model.YouTubeSubscription
import com.lowtube.android.model.YouTubePlaylist
import com.lowtube.android.ui.library.LibraryScreen
import com.lowtube.android.ui.queue.QueueScreen
import com.lowtube.android.ui.downloads.DownloadsScreen
import com.lowtube.android.ui.playback.PlaybackScreen
import com.lowtube.android.benchmark.DecoderBenchmarkScreen
import kotlinx.coroutines.launch

@Composable
fun MainNavigation(
    googleAccountName: String,
    googleAccountEmail: String,
    googleAccountAvatarUrl: String = "",
    googleAccountMessage: String,
    googleSignInBusy: Boolean,
    youtubeSyncBusy: Boolean,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onRequestYouTubeSync: ((Result<List<YouTubeSubscription>>) -> Unit) -> Unit,
    onRequestYouTubePlaylists: ((Result<List<YouTubePlaylist>>) -> Unit) -> Unit,
    onRequestYouTubePlaylistVideos: (String, (Result<List<VideoItem>>) -> Unit) -> Unit,
    onChangeYouTubeSubscription: (String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onBackHandlerChanged: ((() -> Boolean) -> Unit),
    externalYoutubeUrl: String?,
    externalYoutubeRequestId: Int,
    onExternalYoutubeUrlConsumed: (Int) -> Unit,
    inPictureInPictureMode: Boolean,
    onPictureInPictureEligibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val library = remember(context) { LocalLibraryRepository(context.applicationContext) }
    val catalogue = remember(context) { InnerTubeSearchRepository(context.applicationContext) }
    val downloads = remember(context) { DownloadRepository(context) }
    val playbackSessionStore = remember(context) { PlaybackSessionStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE) }
    val currentGoogleAccountEmail by rememberUpdatedState(googleAccountEmail)
    DisposableEffect(library) {
        library.localizeWatchLater(context.getString(R.string.watch_later))
        onDispose { library.close() }
    }
    var libraryRevision by remember(library) { mutableIntStateOf(0) }
    var pendingYouTubeSubscriptionChanges by remember(library) { mutableStateOf(emptyMap<String, Boolean>()) }
    var previousGoogleAccountEmail by remember(library) { mutableStateOf(googleAccountEmail) }
    LaunchedEffect(library, googleAccountEmail) {
        if (!previousGoogleAccountEmail.equals(googleAccountEmail, ignoreCase = true)) {
            library.clearYouTubeAccountSubscriptions()
            libraryRevision++
        }
        previousGoogleAccountEmail = googleAccountEmail
    }
    var queueRevision by remember(library) { mutableIntStateOf(0) }
    var expandingQueueRowId by remember(library) { mutableStateOf<Long?>(null) }
    var followedChannels by remember(library) { mutableStateOf(library.localChannels().map { it.id }.toSet()) }
    val youtubeFollowedChannels = remember(library, libraryRevision, googleAccountEmail) {
        library.channels(googleAccountEmail).filter { it.fromYouTubeAccount }.map { it.id }.toSet()
    }
    val toggleFollowChannel: (VideoItem) -> Boolean = { video ->
        if (googleAccountEmail.isNotBlank()) {
            val channelId = video.channelId
            val pending = pendingYouTubeSubscriptionChanges[channelId]
            if (pending != null) {
                pending || channelId in followedChannels
            } else {
                val current = channelId in youtubeFollowedChannels
                val requested = !current
                pendingYouTubeSubscriptionChanges = pendingYouTubeSubscriptionChanges + (channelId to requested)
                onChangeYouTubeSubscription(channelId, requested) { result ->
                    result.onSuccess {
                        runCatching {
                            library.setYouTubeAccountSubscription(
                                googleAccountEmail,
                                channelId,
                                video.channel.ifBlank { channelId },
                                requested,
                            )
                        }
                            .onSuccess { libraryRevision++ }
                            .onFailure { Toast.makeText(context, R.string.youtube_subscription_change_failed, Toast.LENGTH_LONG).show() }
                    }.onFailure { failure ->
                        if (failure.message?.contains("cancel", ignoreCase = true) != true) {
                            Toast.makeText(context, R.string.youtube_subscription_change_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    pendingYouTubeSubscriptionChanges = pendingYouTubeSubscriptionChanges - channelId
                }
                requested || channelId in followedChannels
            }
        } else {
            runCatching {
                val nowFollowing = if (video.channelId in followedChannels) {
                    library.unfollow(video.channelId)
                    followedChannels = followedChannels - video.channelId
                    false
                } else {
                    library.follow(video.channelId, video.channel, "https://www.youtube.com/channel/${video.channelId}")
                    followedChannels = followedChannels + video.channelId
                    true
                }
                libraryRevision++
                nowFollowing
            }.onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                .getOrDefault(video.channelId in followedChannels)
        }
    }
    var youtubeSyncMessage by remember { mutableStateOf("") }
    var youtubePlaylists by remember { mutableStateOf(emptyList<YouTubePlaylist>()) }
    var youtubePlaylistsMessage by remember { mutableStateOf("") }
    var youtubePlaylistsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(googleAccountEmail) {
        youtubePlaylists = emptyList()
        youtubePlaylistsMessage = ""
        youtubePlaylistsBusy = false
    }
    val restoredPlaybackSession = remember(library, playbackSessionStore) {
        playbackSessionStore.load()?.let { session ->
            val stillQueued = session.queueRowId?.let { rowId -> library.queue().any { it.rowId == rowId } } == true
            session.copy(queueRowId = session.queueRowId.takeIf { stillQueued })
        }
    }
    var selectedVideo by rememberSaveable(stateSaver = videoItemSaver) { mutableStateOf(restoredPlaybackSession?.video) }
    var shortsSession by remember { mutableStateOf(emptyList<VideoItem>()) }
    var shortsSessionIndex by remember { mutableIntStateOf(-1) }
    var selectedLocalAudio by rememberSaveable(stateSaver = localAudioTrackSaver) {
        mutableStateOf(restoredPlaybackSession?.localAudioTrack)
    }
    var selectedLocalVideoSource by remember { mutableStateOf(restoredPlaybackSession?.localVideoSource) }
    var selectedQueueRowId by rememberSaveable { mutableStateOf(restoredPlaybackSession?.queueRowId) }
    var playbackRequestId by rememberSaveable { mutableLongStateOf(0L) }
    var advancingQueue by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<VideoItem?>(null) }
    var pendingDownloadOption by remember { mutableStateOf<DownloadFormatOption?>(null) }
    var browseChannelRequest by remember { mutableStateOf<String?>(null) }
    var discoveryTopic by remember(context) { mutableStateOf(preferences.getString("discovery_topic", "science and technology") ?: "science and technology") }
    var discoveryLanguage by remember(context) { mutableStateOf(preferences.getString("discovery_language", "any") ?: "any") }
    var playbackPreferences by remember(context) {
        val preferences = context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE)
        mutableStateOf(
            PlaybackStreamPreferences(
                maxHeight = preferences.getInt("playback_height", 720),
                h264Only = preferences.getBoolean("playback_h264", true),
                fps30Only = preferences.getBoolean("playback_fps30", true),
                audioOnly = preferences.getBoolean("playback_audio_only", false),
            ),
        )
    }
    var playbackBufferPreferences by remember(context) {
        mutableStateOf(
            PlaybackBufferPreferences.fromStoredValues(
                maximumSeconds = preferences.getInt("playback_buffer_seconds", PlaybackBufferPreferences.DEFAULT_MAXIMUM_SECONDS),
                memoryLimitMiB = preferences.getInt("playback_buffer_mib", PlaybackBufferPreferences.DEFAULT_MEMORY_LIMIT_MIB),
                backBufferMiB = preferences.getInt("playback_back_buffer_mib", PlaybackBufferPreferences.DEFAULT_BACK_BUFFER_MIB),
                startupSeconds = preferences.getInt("playback_start_buffer_seconds", PlaybackBufferPreferences.DEFAULT_STARTUP_SECONDS),
            ),
        )
    }
    var downloadPreferences by remember(context) {
        mutableStateOf(
            DownloadStreamPreferences(
                maxHeight = preferences.getInt("download_height", 720),
                h264Only = preferences.getBoolean("download_h264", true),
                fps30Only = preferences.getBoolean("download_fps30", false),
                limitKiB = preferences.getInt("download_limit_kib", 0),
                audioBitrateKbps = preferences.getInt("download_audio_bitrate", DownloadStreamPreferences.DEFAULT_AUDIO_BITRATE_KBPS),
            ),
        )
    }
    var playbackSpeed by remember(context) {
        mutableStateOf(
            context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE)
                .getFloat("playback_speed", 1f).coerceIn(0.25f, 2f),
        )
    }
    var resumePlayback by remember(context) { mutableStateOf(preferences.getBoolean("resume_playback", true)) }
    var autoRecover by remember(context) { mutableStateOf(preferences.getBoolean("auto_recover", true)) }
    var autoNext by remember(context) { mutableStateOf(preferences.getBoolean("playback_auto_next", true)) }
    var preloadNext by remember(context) { mutableStateOf(preferences.getBoolean("playback_preload_next", true)) }
    var feedRefreshPreset by remember(context) { mutableIntStateOf(preferences.getInt("feed_refresh_preset", 1).coerceIn(0, 2)) }
    var pageSize by remember(context) { mutableIntStateOf(preferences.getInt("page_size", 25).coerceIn(1, 250)) }
    LaunchedEffect(selectedVideo, selectedQueueRowId, selectedLocalAudio, selectedLocalVideoSource) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            playbackSessionStore.save(selectedVideo, selectedQueueRowId, selectedLocalAudio, selectedLocalVideoSource)
        }
    }
    LaunchedEffect(library, libraryRevision, feedRefreshPreset) {
        val hasLocalChannels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            library.localChannels().isNotEmpty()
        }
        SubscriptionFeedRefreshScheduler.synchronize(context.applicationContext, feedRefreshPreset, hasLocalChannels)
    }
    var mainTab by rememberSaveable { mutableStateOf("home") }
    var returnMainTab by rememberSaveable { mutableStateOf("home") }
    val hasQueuedItems = remember(library, queueRevision) { library.queue().isNotEmpty() }
    val queueHeadRowId = remember(library, queueRevision) { library.queue().firstOrNull()?.rowId }
    val peekNextQueueEntry: suspend () -> QueueEntry? = peek@{
        var expandedPages = 0
        while (expandedPages < 3) {
            val next = library.queue().firstOrNull() ?: return@peek null
            if (next.video.kind != "playlist") return@peek next
            val page = catalogue.search(
                next.video.url,
                continuation = next.continuation.ifBlank { null },
                language = AppLocale.language(context),
            )
            library.expandQueuedPlaylist(next.rowId, page.items, page.continuation)
            queueRevision++
            expandedPages++
        }
        library.queue().firstOrNull()?.takeIf { it.video.kind != "playlist" }
    }
    val advanceQueuedPlayback: () -> Unit = advance@{
        if (advancingQueue) return@advance
        advancingQueue = true
        scope.launch {
            try {
                var expandedPages = 0
                while (expandedPages < 3) {
                    val next = library.queue().firstOrNull() ?: break
                    if (next.video.kind == "playlist") {
                        val page = catalogue.search(
                            next.video.url,
                            continuation = next.continuation.ifBlank { null },
                            language = AppLocale.language(context),
                        )
                        library.expandQueuedPlaylist(next.rowId, page.items, page.continuation)
                        queueRevision++
                        expandedPages++
                        continue
                    }
                    selectedQueueRowId = next.rowId
                    selectedLocalAudio = null
                    selectedLocalVideoSource = null
                    selectedVideo = next.video
                    playbackRequestId++
                    break
                }
            } catch (failure: Exception) {
                Toast.makeText(context, failure.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show()
            } finally {
                advancingQueue = false
            }
        }
    }
    val startDownload: (VideoItem, DownloadFormatOption) -> Unit = { video, option ->
        scope.launch {
            runCatching { downloads.enqueue(video, option) }
                .onSuccess { Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.download_failed), Toast.LENGTH_LONG).show() }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val video = pendingDownload
        val option = pendingDownloadOption
        pendingDownload = null
        pendingDownloadOption = null
        val granted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (granted && video != null && option != null) startDownload(video, option)
        else if (!granted) Toast.makeText(context, R.string.storage_permission_required, Toast.LENGTH_LONG).show()
    }
    val beginDownload: (VideoItem, DownloadFormatOption) -> Unit = { video, option ->
        val needsLegacyStoragePermissions = Build.VERSION.SDK_INT <= 28 &&
            DownloadDestination.selectedTreeUri(context) == null && (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            )
        if (needsLegacyStoragePermissions) {
            pendingDownload = video
            pendingDownloadOption = option
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else startDownload(video, option)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val video = pendingDownload
        val option = pendingDownloadOption
        pendingDownload = null
        pendingDownloadOption = null
        if (!granted) Toast.makeText(context, R.string.download_notifications_disabled, Toast.LENGTH_LONG).show()
        if (video != null && option != null) beginDownload(video, option)
    }
    val requestDownload: (VideoItem, DownloadFormatOption) -> Unit = { video, option ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = video
            pendingDownloadOption = option
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginDownload(video, option)
    }
    val localAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        var skipped = 0
        for (uri in uris.distinct()) {
            val metadata = readPickedAudioMetadata(context, uri)
            if (metadata == null) {
                skipped++
                continue
            }
            val alreadyPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            val canPersist = alreadyPersisted || runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)
            if (!canPersist) {
                skipped++
                continue
            }
            val saved = runCatching { library.saveLocalAudioTrack(uri.toString(), metadata.title, metadata.mimeType) }.getOrDefault(false)
            if (saved) added++ else {
                skipped++
                if (!alreadyPersisted) runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        if (added > 0) libraryRevision++
        Toast.makeText(context, context.getString(R.string.audio_import_result, added, skipped), Toast.LENGTH_LONG).show()
    }
    val backStack = rememberNavBackStack(Main)
    LaunchedEffect(externalYoutubeRequestId, externalYoutubeUrl) {
        val url = externalYoutubeUrl?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        while (backStack.size > 1) backStack.removeLastOrNull()
        mainTab = "search"
        browseChannelRequest = url
        onExternalYoutubeUrlConsumed(externalYoutubeRequestId)
    }
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
            if (backStack.lastOrNull() == Main && mainTab in setOf("settings", "library")) {
                mainTab = returnMainTab
            }
        }
    }
    SideEffect {
        onBackHandlerChanged {
            if (backStack.size > 1) { navigateBack(); true } else false
        }
    }
    val localPlaylists = remember(library, libraryRevision, backStack.size) { library.playlists() }
    val addChannel: (String) -> Unit = { input ->
        scope.launch {
            runCatching { catalogue.resolveChannel(input, AppLocale.language(context)) }
                .onSuccess { channel ->
                    runCatching { library.follow(channel.id, channel.title, channel.url) }
                        .onSuccess {
                            followedChannels = followedChannels + channel.id
                            libraryRevision++
                            Toast.makeText(context, R.string.channel_added, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                }
                .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
        }
    }
    NavDisplay(
        backStack = backStack,
        onBack = navigateBack,
        entryProvider = entryProvider {
            entry<Main> {
                LowTubeSearchScreen(
                    googleAccountName = googleAccountName,
                    googleAccountEmail = googleAccountEmail,
                    googleAccountAvatarUrl = googleAccountAvatarUrl,
                    googleAccountMessage = googleAccountMessage,
                    googleSignInBusy = googleSignInBusy,
                    onGoogleSignIn = onGoogleSignIn,
                    onGoogleSignOut = {
                        library.clearYouTubeAccountSubscriptions()
                        libraryRevision++
                        onGoogleSignOut()
                    },
                    youtubeSyncBusy = youtubeSyncBusy,
                    youtubeSyncMessage = youtubeSyncMessage,
                    onSyncYouTubeSubscriptions = {
                        youtubeSyncMessage = ""
                        onRequestYouTubeSync { result ->
                            if (currentGoogleAccountEmail.isBlank()) {
                                youtubeSyncMessage = context.getString(R.string.youtube_sync_failed)
                                return@onRequestYouTubeSync
                            }
                            result.onSuccess { subscriptions ->
                                runCatching { library.replaceYouTubeAccountSubscriptions(currentGoogleAccountEmail, subscriptions) }
                                    .onSuccess {
                                        libraryRevision++
                                        youtubeSyncMessage = context.getString(R.string.youtube_sync_success, subscriptions.size)
                                    }
                                    .onFailure { youtubeSyncMessage = it.message ?: context.getString(R.string.youtube_sync_failed) }
                            }.onFailure { youtubeSyncMessage = it.message ?: context.getString(R.string.youtube_sync_failed) }
                        }
                    },
                    onOpenSettings = { tab -> returnMainTab = tab; backStack.add(Settings) },
                    onOpenLibrary = { tab -> returnMainTab = tab; backStack.add(Library) },
                    selectedTab = mainTab,
                    onSelectedTabChanged = { mainTab = it },
                    miniPlayerVideo = selectedVideo.takeIf { backStack.lastOrNull() == Main },
                    onOpenMiniPlayer = { if (selectedVideo != null) backStack.add(Player) },
                    onOpenQueue = { backStack.add(Queue) },
                    onOpenDownloads = { backStack.add(Downloads) },
                    onOpenDecoderBenchmark = { backStack.add(DecoderBenchmark) },
                    onPlay = { video ->
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        selectedQueueRowId = null
                        selectedLocalAudio = null
                        selectedLocalVideoSource = null
                        selectedVideo = video
                        playbackRequestId++
                        backStack.add(Player)
                    },
                    onPlayShorts = { video, results ->
                        shortsSession = results.filter { it.kind == "shorts" }
                        shortsSessionIndex = shortsSession.indexOfFirst { it.id == video.id }
                        selectedQueueRowId = null
                        selectedLocalAudio = null
                        selectedLocalVideoSource = null
                        selectedVideo = video
                        playbackRequestId++
                        backStack.add(Player)
                    },
                    onResolveDownloadOptions = { video, audioOnly, callback ->
                        scope.launch {
                            callback(runCatching { downloads.options(video, downloadPreferences, audioOnly) })
                        }
                    },
                    onDownload = requestDownload,
                    channelBrowseRequest = browseChannelRequest,
                    onChannelBrowseConsumed = { browseChannelRequest = null },
                    localPlaylists = localPlaylists,
                    onAddToQueue = { video ->
                        runCatching { library.enqueue(video) }
                            .onSuccess {
                                queueRevision++
                                Toast.makeText(context, R.string.added_to_queue, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                    onSaveVideo = { video ->
                        runCatching { library.saveToWatchLater(video, context.getString(R.string.watch_later)) }
                            .onSuccess {
                                libraryRevision++
                                Toast.makeText(context, R.string.saved_to_watch_later, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                    onSaveToPlaylist = { video, playlistId ->
                        runCatching { library.addVideo(playlistId, video) }
                            .onSuccess {
                                libraryRevision++
                                Toast.makeText(context, R.string.saved_to_playlist, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                    onToggleFollow = toggleFollowChannel,
                    isFollowing = { channelId ->
                        channelId in followedChannels || (pendingYouTubeSubscriptionChanges[channelId] ?: (channelId in youtubeFollowedChannels))
                    },
                    discoveryTopic = discoveryTopic,
                    discoveryLanguage = discoveryLanguage,
                    feedRefreshPreset = feedRefreshPreset,
                    pageSize = pageSize,
                    libraryRepository = library,
                    libraryRevision = libraryRevision,
                    onAddChannel = addChannel,
                    onBrowseChannel = { browseChannelRequest = it },
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onDarkThemeChanged = onDarkThemeChanged,
                    onLanguageChanged = onLanguageChanged,
                    onBack = navigateBack,
                    discoveryTopic = discoveryTopic,
                    discoveryLanguage = discoveryLanguage,
                    feedRefreshPreset = feedRefreshPreset,
                    pageSize = pageSize,
                    onFeedRefreshPresetChanged = { preset ->
                        feedRefreshPreset = preset.coerceIn(0, 2)
                        preferences.edit().putInt("feed_refresh_preset", feedRefreshPreset).apply()
                    },
                    onPageSizeChanged = { value ->
                        pageSize = value.coerceIn(1, 250)
                        preferences.edit().putInt("page_size", pageSize).apply()
                    },
                    onDiscoveryPreferencesChanged = { topic, language ->
                        discoveryTopic = topic.take(200)
                        discoveryLanguage = language
                        preferences.edit()
                            .putString("discovery_topic", discoveryTopic)
                            .putString("discovery_language", discoveryLanguage)
                            .apply()
                    },
                    playbackPreferences = playbackPreferences,
                    playbackBufferPreferences = playbackBufferPreferences,
                    onPlaybackBufferPreferencesChanged = { updated ->
                        playbackBufferPreferences = updated
                        preferences.edit()
                            .putInt("playback_buffer_seconds", updated.maximumSeconds)
                            .putInt("playback_buffer_mib", updated.memoryLimitMiB)
                            .putInt("playback_back_buffer_mib", updated.backBufferMiB)
                            .putInt("playback_start_buffer_seconds", updated.startupSeconds)
                            .apply()
                    },
                    onPlaybackPreferencesChanged = { updated ->
                        playbackPreferences = updated
                        context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE).edit()
                            .putInt("playback_height", updated.maxHeight)
                            .putBoolean("playback_h264", updated.h264Only)
                            .putBoolean("playback_fps30", updated.fps30Only)
                            .putBoolean("playback_audio_only", updated.audioOnly)
                            .apply()
                    },
                    downloadPreferences = downloadPreferences,
                    onDownloadPreferencesChanged = { updated ->
                        downloadPreferences = updated
                        preferences.edit()
                            .putInt("download_height", updated.maxHeight)
                            .putBoolean("download_h264", updated.h264Only)
                            .putBoolean("download_fps30", updated.fps30Only)
                            .putInt("download_limit_kib", updated.limitKiB)
                            .putInt("download_audio_bitrate", updated.audioBitrateKbps)
                            .apply()
                    },
                    resumePlayback = resumePlayback,
                    onResumePlaybackChanged = { enabled ->
                        resumePlayback = enabled
                        preferences.edit().putBoolean("resume_playback", enabled).apply()
                    },
                    autoRecover = autoRecover,
                    onAutoRecoverChanged = { enabled ->
                        autoRecover = enabled
                        preferences.edit().putBoolean("auto_recover", enabled).apply()
                    },
                    autoNext = autoNext,
                    onAutoNextChanged = { enabled ->
                        autoNext = enabled
                        preferences.edit().putBoolean("playback_auto_next", enabled).apply()
                    },
                    preloadNext = preloadNext,
                    onPreloadNextChanged = { enabled ->
                        preloadNext = enabled
                        preferences.edit().putBoolean("playback_preload_next", enabled).apply()
                    },
                    downloadRepository = downloads,
                )
            }
            entry<Library> {
                LibraryScreen(
                    repository = library,
                    accountEmail = googleAccountEmail,
                    refreshRevision = libraryRevision,
                    feedRefreshPreset = feedRefreshPreset,
                    youtubePlaylists = youtubePlaylists,
                    youtubePlaylistsBusy = youtubePlaylistsBusy || youtubeSyncBusy,
                    youtubePlaylistsMessage = youtubePlaylistsMessage,
                    onLoadYouTubePlaylistVideos = { playlistId, callback ->
                        if (youtubeSyncBusy) callback(Result.failure(IllegalStateException("Another YouTube account request is already in progress.")))
                        else onRequestYouTubePlaylistVideos(playlistId, callback)
                    },
                    onLoadYouTubePlaylists = {
                        if (!youtubePlaylistsBusy && !youtubeSyncBusy) {
                            youtubePlaylistsBusy = true
                            youtubePlaylistsMessage = ""
                            onRequestYouTubePlaylists { result ->
                                youtubePlaylistsBusy = false
                                if (currentGoogleAccountEmail.equals(googleAccountEmail, ignoreCase = true)) {
                                    result.onSuccess { playlists ->
                                        youtubePlaylists = playlists
                                        youtubePlaylistsMessage = context.getString(R.string.youtube_playlists_loaded, playlists.size)
                                    }.onFailure { failure ->
                                        youtubePlaylistsMessage = failure.message ?: context.getString(R.string.youtube_playlists_failed)
                                    }
                                } else {
                                    youtubePlaylists = emptyList()
                                    youtubePlaylistsMessage = context.getString(R.string.youtube_playlists_failed)
                                }
                            }
                        }
                    },
                    onBack = navigateBack,
                    onPlay = { video ->
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        selectedQueueRowId = null
                        selectedLocalAudio = null
                        selectedLocalVideoSource = null
                        selectedVideo = video
                        playbackRequestId++
                        backStack.add(Player)
                    },
                    onPlayPlaylist = { videos ->
                        val first = videos.firstOrNull()
                        if (first != null) {
                            runCatching { library.enqueueAllAtFront(videos.drop(1)) }
                                .onSuccess {
                                    queueRevision++
                                    selectedQueueRowId = null
                                    selectedLocalAudio = null
                                    selectedLocalVideoSource = null
                                    shortsSession = emptyList()
                                    shortsSessionIndex = -1
                                    selectedVideo = first
                                    playbackRequestId++
                                    backStack.add(Player)
                                }
                                .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                        }
                    },
                    onPickAudio = { localAudioPicker.launch(arrayOf("audio/*")) },
                    onPlayLocalAudio = { track ->
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        selectedQueueRowId = null
                        selectedLocalAudio = track
                        selectedLocalVideoSource = null
                        selectedVideo = VideoItem(
                            id = "local:${track.uri}",
                            title = track.title,
                            kind = "local-audio",
                            url = track.uri,
                        )
                        playbackRequestId++
                        backStack.add(Player)
                    },
                    onRemoveLocalAudio = { track ->
                        val removed = library.removeLocalAudioTrack(track.uri)
                        if (removed) {
                            libraryRevision++
                            runCatching {
                                context.contentResolver.releasePersistableUriPermission(
                                    Uri.parse(track.uri),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        } else Toast.makeText(context, R.string.audio_remove_not_found, Toast.LENGTH_SHORT).show()
                    },
                    onAddToQueue = { videos ->
                        runCatching { library.enqueueAll(videos) }
                            .onSuccess {
                                queueRevision++
                                Toast.makeText(context, R.string.playlist_added_to_queue, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                    onBrowseChannel = { url ->
                        browseChannelRequest = url
                        navigateBack()
                    },
                    onAddChannel = addChannel,
                )
            }
            entry<Queue> {
                QueueScreen(
                    repository = library,
                    refreshRevision = queueRevision,
                    onBack = navigateBack,
                    onPlay = { entry ->
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        selectedQueueRowId = entry.rowId
                        selectedLocalAudio = null
                        selectedLocalVideoSource = null
                        selectedVideo = entry.video
                        playbackRequestId++
                        backStack.add(Player)
                    },
                    onExpandPlaylist = { entry ->
                        if (expandingQueueRowId == null) {
                            expandingQueueRowId = entry.rowId
                            scope.launch {
                                try {
                                val page = catalogue.search(
                                    entry.video.url,
                                    continuation = entry.continuation.ifBlank { null },
                                    language = AppLocale.language(context),
                                )
                                library.expandQueuedPlaylist(entry.rowId, page.items, page.continuation)
                                    queueRevision++
                                    val message = if (entry.continuation.isBlank()) R.string.playlist_page_added else R.string.playlist_next_page_added
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                } catch (failure: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                        Toast.makeText(context, failure.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    expandingQueueRowId = null
                                }
                            }
                        }
                    },
                    expandingPlaylistRowId = expandingQueueRowId,
                    autoNext = autoNext,
                    onAutoNextChanged = { enabled ->
                        autoNext = enabled
                        preferences.edit().putBoolean("playback_auto_next", enabled).apply()
                    },
                )
            }
            entry<Downloads> {
                DownloadsScreen(
                    repository = downloads,
                    onBack = navigateBack,
                    onRetry = { id, refresh ->
                        scope.launch {
                            runCatching { downloads.retry(id, downloadPreferences) }
                            .onSuccess { Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.download_failed), Toast.LENGTH_LONG).show() }
                            refresh()
                        }
                    },
                    onPlayInLowTube = { id ->
                        runCatching { downloads.playableVideo(id) }
                            .onSuccess { downloaded ->
                                shortsSession = emptyList()
                                shortsSessionIndex = -1
                                selectedQueueRowId = null
                                selectedLocalAudio = null
                                selectedVideo = downloaded.video
                                selectedLocalVideoSource = downloaded.source
                                playbackRequestId++
                                backStack.add(Player)
                            }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                    onOpen = { id ->
                        runCatching { downloads.open(id) }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
                    },
                )
            }
            entry<DecoderBenchmark> {
                DecoderBenchmarkScreen(onBack = navigateBack)
            }
            entry<Player> {
                val video = selectedVideo
                if (video == null) backStack.removeLastOrNull()
                else PlaybackScreen(
                    video = video,
                    playbackRequestId = playbackRequestId,
                    isFollowing = video.channelId in followedChannels || (pendingYouTubeSubscriptionChanges[video.channelId] ?: (video.channelId in youtubeFollowedChannels)),
                    onToggleFollow = toggleFollowChannel,
                    localAudioTrack = selectedLocalAudio,
                    localVideoSource = selectedLocalVideoSource,
                    library = library,
                    playbackPreferences = playbackPreferences,
                    playbackSpeed = playbackSpeed,
                    resumePlayback = resumePlayback,
                    autoRecover = autoRecover,
                    autoNext = autoNext,
                    preloadNext = preloadNext,
                    hasQueuedItems = hasQueuedItems,
                    queueHeadRowId = queueHeadRowId,
                    onResolveNextQueueEntry = peekNextQueueEntry,
                    onIsQueueHead = { rowId -> library.queue().firstOrNull()?.rowId == rowId },
                    onPreloadedQueueEntryStarted = { entry ->
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        library.removeQueueEntry(entry.rowId)
                        selectedQueueRowId = null
                        selectedLocalAudio = null
                        selectedLocalVideoSource = null
                        selectedVideo = entry.video
                        queueRevision++
                    },
                    onPlaybackReady = {
                        selectedQueueRowId?.let { rowId ->
                            library.removeQueueEntry(rowId)
                            selectedQueueRowId = null
                            queueRevision++
                        }
                    },
                    onAdvanceQueue = advanceQueuedPlayback,
                    onPreviousVideo = {
                        shortsSession = emptyList()
                        shortsSessionIndex = -1
                        val previous = library.previousWatchedVideo(video.id)
                        if (previous == null) Toast.makeText(context, R.string.no_previous_video, Toast.LENGTH_SHORT).show()
                        else { selectedQueueRowId = null; selectedLocalAudio = null; selectedLocalVideoSource = null; selectedVideo = previous; playbackRequestId++ }
                    },
                    onPlaybackSpeedChanged = { updated ->
                        playbackSpeed = updated.coerceIn(0.25f, 2f)
                        context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE).edit()
                            .putFloat("playback_speed", playbackSpeed)
                            .apply()
                    },
                    onBack = navigateBack,
                    canNavigateShortsBack = video.kind == "shorts" && shortsSessionIndex > 0,
                    canNavigateShortsForward = video.kind == "shorts" && shortsSessionIndex in 0 until shortsSession.lastIndex,
                    onNavigateShorts = { direction ->
                        val targetIndex = shortsSessionIndex + direction
                        val target = shortsSession.getOrNull(targetIndex)
                        if (target != null && target.kind == "shorts") {
                            selectedQueueRowId = null
                            selectedLocalAudio = null
                            selectedLocalVideoSource = null
                            selectedVideo = target
                            shortsSessionIndex = targetIndex
                            playbackRequestId++
                        }
                    },
                    inPictureInPictureMode = inPictureInPictureMode,
                    onPictureInPictureEligibilityChanged = onPictureInPictureEligibilityChanged,
                )
            }
        },
    )
}

private val videoItemSaver = mapSaver<VideoItem?>(
    save = { video ->
        if (video == null) emptyMap()
        else mapOf(
            "id" to video.id,
            "title" to video.title,
            "channel" to video.channel,
            "channelId" to video.channelId,
            "published" to video.published,
            "duration" to video.duration,
            "kind" to video.kind,
            "liveStatus" to video.liveStatus,
            "thumbnailUrl" to video.thumbnailUrl,
            "url" to video.url,
            "publishedAtMs" to video.publishedAtMs,
            "channelAvatarUrl" to video.channelAvatarUrl,
        )
    },
    restore = { saved ->
        if (saved.isEmpty()) null
        else VideoItem(
            id = saved["id"] as String,
            title = saved["title"] as String,
            channel = saved["channel"] as String,
            channelId = saved["channelId"] as String,
            published = saved["published"] as String,
            duration = saved["duration"] as String,
            kind = saved["kind"] as String,
            liveStatus = saved["liveStatus"] as String,
            thumbnailUrl = saved["thumbnailUrl"] as String,
            url = saved["url"] as String,
            publishedAtMs = saved["publishedAtMs"] as Long,
            channelAvatarUrl = saved["channelAvatarUrl"] as? String ?: "",
        )
    },
)

private val localAudioTrackSaver = mapSaver<LocalAudioTrack?>(
    save = { track ->
        if (track == null) emptyMap()
        else mapOf(
            "uri" to track.uri,
            "title" to track.title,
            "mimeType" to track.mimeType,
            "addedAt" to track.addedAt,
        )
    },
    restore = { saved ->
        if (saved.isEmpty()) null
        else LocalAudioTrack(
            uri = saved["uri"] as String,
            title = saved["title"] as String,
            mimeType = saved["mimeType"] as String,
            addedAt = saved["addedAt"] as Long,
        )
    },
)

private data class PickedAudioMetadata(val title: String, val mimeType: String)

private fun readPickedAudioMetadata(context: Context, uri: Uri): PickedAudioMetadata? {
    if (uri.scheme != "content") return null
    val displayName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
    val resolvedMime = context.contentResolver.getType(uri)
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
        ?: displayName.substringAfterLast('.', "").let { extension -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) }
            ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
        ?: return null
    return PickedAudioMetadata(displayName.take(512), resolvedMime)
}
