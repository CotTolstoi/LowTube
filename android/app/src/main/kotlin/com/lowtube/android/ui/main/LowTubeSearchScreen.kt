package com.lowtube.android.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.lowtube.android.R
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.LocalPlaylist
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.LocalSearchHistoryRepository
import com.lowtube.android.ui.playback.MiniPlayerBar
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LowTubeSearchScreen(
    googleAccountName: String,
    googleAccountEmail: String,
    googleAccountMessage: String,
    googleSignInBusy: Boolean,
    youtubeSyncBusy: Boolean,
    youtubeSyncMessage: String,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onSyncYouTubeSubscriptions: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenLibrary: (String) -> Unit,
    selectedTab: String,
    onSelectedTabChanged: (String) -> Unit,
    miniPlayerVideo: VideoItem?,
    onOpenMiniPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDecoderBenchmark: () -> Unit = {},
    onPlay: (VideoItem) -> Unit,
    onPlayShorts: (VideoItem, List<VideoItem>) -> Unit,
    onAddToQueue: (VideoItem) -> Unit,
    onResolveDownloadOptions: (VideoItem, Boolean, (Result<List<DownloadFormatOption>>) -> Unit) -> Unit,
    onDownload: (VideoItem, DownloadFormatOption) -> Unit,
    onSaveVideo: (VideoItem) -> Unit,
    onSaveToPlaylist: (VideoItem, String) -> Unit,
    localPlaylists: List<LocalPlaylist>,
    channelBrowseRequest: String?,
    onChannelBrowseConsumed: () -> Unit,
    onToggleFollow: (VideoItem) -> Boolean,
    isFollowing: (String) -> Boolean,
    discoveryTopic: String,
    discoveryLanguage: String,
    feedRefreshPreset: Int = 1,
    pageSize: Int = 25,
    libraryRepository: LocalLibraryRepository,
    libraryRevision: Int,
    onAddChannel: (String) -> Unit,
    onBrowseChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(),
    searchHistoryRepository: LocalSearchHistoryRepository? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val searchHistory = remember(context, searchHistoryRepository) {
        searchHistoryRepository ?: LocalSearchHistoryRepository(context)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var recentSearches by remember(searchHistory) { mutableStateOf(searchHistory.recent()) }
    var searchKind by rememberSaveable { mutableStateOf("all") }
    var recommendationKind by rememberSaveable { mutableStateOf("all") }
    var autoLoadAttemptedToken by remember(state.query, state.isHome, state.homeCacheKey, selectedTab, state.displayLimit) { mutableStateOf<String?>(null) }
    var saveTarget by remember { mutableStateOf<VideoItem?>(null) }
    var downloadTarget by remember { mutableStateOf<VideoItem?>(null) }
    var downloadOptions by remember { mutableStateOf<List<DownloadFormatOption>?>(null) }
    var downloadOptionsLoading by remember { mutableStateOf(false) }
    var downloadOptionsError by remember { mutableStateOf<String?>(null) }
    var downloadAudioOnly by remember { mutableStateOf(false) }
    var downloadRequestGeneration by remember { mutableStateOf(0) }
    var moreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var followedChannelItems by remember(libraryRepository) { mutableStateOf(emptyList<VideoItem>()) }
    val language = currentLanguage()
    val emptySearchMessage = stringResource(R.string.search_empty_query)
    val requestFailedMessage = stringResource(R.string.request_failed_retry)
    val downloadFailedMessage = stringResource(R.string.download_failed)
    val listState = rememberLazyListState()
    val forYouItems = remember(state.items, state.isHome, selectedTab, followedChannelItems) {
        if (state.isHome && selectedTab == "home") {
            ForYouMixer.blend(state.items, followedChannelItems)
        } else state.items
    }
    val channelBrowse = remember(query, selectedTab) {
        if (selectedTab == "search") channelBrowseTab(query) else null
    }
    val activeKind = when {
        selectedTab == "home" -> recommendationKind
        channelBrowse != null -> channelBrowse.kind
        else -> searchKind
    }
    val visibleItems = remember(forYouItems, activeKind, channelBrowse) {
        if (channelBrowse != null || activeKind == "all") forYouItems
        else forYouItems.filter { it.kind == activeKind }
    }
    val displayedItems = remember(visibleItems, state.displayLimit) { visibleItems.take(state.displayLimit) }
    val directPlaylistQueueItem = remember(query, selectedTab, state.playlistTitle) {
        if (selectedTab == "search") {
            youtubePlaylistQueueMarker(query, state.playlistTitle?.takeIf(String::isNotBlank) ?: context.getString(R.string.playlist))
        } else null
    }
    val executeSearch: (String) -> Unit = { input ->
        val normalized = input.trim()
        if (normalized.isNotEmpty()) {
            recentSearches = searchHistory.record(normalized)
            val target = channelBrowseUrl(normalized) ?: normalized
            query = target
            searchKind = channelBrowseTab(target)?.kind ?: "all"
            onSelectedTabChanged("search")
            viewModel.search(target, language, emptySearchMessage, requestFailedMessage, pageSize)
        }
        searchFocused = false
        focusManager.clearFocus()
    }
    val searchAction = { executeSearch(query) }
    val dismissDownloadDialog = {
        downloadRequestGeneration++
        downloadTarget = null
        downloadOptions = null
        downloadOptionsLoading = false
        downloadOptionsError = null
    }
    val requestDownloadOptions: (VideoItem, Boolean) -> Unit = { video, audioOnly ->
        val generation = downloadRequestGeneration + 1
        downloadRequestGeneration = generation
        downloadAudioOnly = audioOnly
        downloadOptions = null
        downloadOptionsError = null
        downloadOptionsLoading = true
        onResolveDownloadOptions(video, audioOnly) { result ->
            if (downloadRequestGeneration == generation && downloadTarget?.id == video.id) {
                downloadOptionsLoading = false
                result.onSuccess { downloadOptions = it }
                    .onFailure { downloadOptionsError = it.message ?: downloadFailedMessage }
            }
        }
    }

    LaunchedEffect(language, requestFailedMessage, selectedTab, discoveryTopic, discoveryLanguage) {
        if (selectedTab == "home") {
            viewModel.loadHome(language, discoveryTopic, discoveryLanguage, requestFailedMessage = requestFailedMessage, pageSize = pageSize)
        }
    }

    LaunchedEffect(pageSize) { viewModel.setPageSize(pageSize) }

    LaunchedEffect(libraryRepository, libraryRevision, selectedTab) {
        if (selectedTab == "home") {
            followedChannelItems = withContext(Dispatchers.IO) { libraryRepository.subscriptionFeed() }
        }
    }

    LaunchedEffect(channelBrowseRequest, language) {
        val request = channelBrowseRequest?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val target = channelBrowseUrl(request, "video") ?: request
        query = target
        searchKind = channelBrowseTab(target)?.kind ?: "all"
        onSelectedTabChanged("search")
        viewModel.search(target, language, emptySearchMessage, requestFailedMessage, pageSize)
        onChannelBrowseConsumed()
    }

    LaunchedEffect(state.query) {
        if (state.query.isNotEmpty()) query = state.query
    }

    LaunchedEffect(
        selectedTab,
        state.query,
        state.isHome,
        state.homeCacheKey,
        state.continuation,
        state.loading,
        state.loadingMore,
        displayedItems.size,
        state.displayLimit,
    ) {
        if (selectedTab != "search" && selectedTab != "home") return@LaunchedEffect
        val continuation = state.continuation ?: return@LaunchedEffect
        val attemptKey = "$continuation|${state.displayLimit}"
        if (state.loading || state.loadingMore || visibleItems.isEmpty() || autoLoadAttemptedToken == attemptKey) return@LaunchedEffect

        fun nearEnd(lastVisibleIndex: Int) = FeedAutoPaginationPolicy.shouldPrefetch(lastVisibleIndex, displayedItems.size)
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (!listState.isScrollInProgress || !nearEnd(lastVisibleIndex)) {
            snapshotFlow {
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to listState.isScrollInProgress
            }.first { (index, isScrolling) -> isScrolling && nearEnd(index) }
        }
        if (autoLoadAttemptedToken == attemptKey) return@LaunchedEffect
        autoLoadAttemptedToken = attemptKey
        viewModel.loadMore(language, requestFailedMessage, pageSize, visibleItems.size)
    }

    val navigationItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = Color.Transparent,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            "subscriptions" -> stringResource(R.string.subscriptions_tab)
                            "profile" -> stringResource(R.string.profile)
                            else -> stringResource(R.string.app_name)
                        },
                    )
                },
                actions = {
                    Box {
                        TextButton(onClick = { moreMenuExpanded = true }) { Text("⋮  " + stringResource(R.string.more_menu)) }
                        DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library)) }, onClick = { moreMenuExpanded = false; onOpenLibrary(selectedTab) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.watch_queue)) }, onClick = { moreMenuExpanded = false; onOpenQueue() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.downloads)) }, onClick = { moreMenuExpanded = false; onOpenDownloads() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.decoder_benchmark)) }, onClick = { moreMenuExpanded = false; onOpenDecoderBenchmark() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { moreMenuExpanded = false; onOpenSettings(selectedTab) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Column {
                miniPlayerVideo?.let { video ->
                    MiniPlayerBar(video = video, onOpenPlayer = onOpenMiniPlayer)
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == "search",
                        onClick = { onSelectedTabChanged("search"); viewModel.showSearch() },
                        icon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
                        label = { Text(stringResource(R.string.search_tab)) },
                        colors = navigationItemColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "home",
                        onClick = {
                            onSelectedTabChanged("home")
                            recommendationKind = "all"
                            viewModel.loadHome(language, discoveryTopic, discoveryLanguage, force = true, requestFailedMessage = requestFailedMessage, pageSize = pageSize)
                        },
                        icon = { Icon(painterResource(R.drawable.ic_nav_home), contentDescription = null) },
                        label = { Text(stringResource(R.string.recommendations_tab)) },
                        colors = navigationItemColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "subscriptions",
                        onClick = { onSelectedTabChanged("subscriptions") },
                        icon = { Icon(painterResource(R.drawable.ic_nav_subscriptions), contentDescription = null) },
                        label = { Text(stringResource(R.string.subscriptions_tab)) },
                        colors = navigationItemColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == "profile",
                        onClick = { onSelectedTabChanged("profile") },
                        icon = { Icon(painterResource(R.drawable.ic_nav_profile), contentDescription = null) },
                        label = { Text(stringResource(R.string.profile)) },
                        colors = navigationItemColors,
                    )
                }
            }
        },
    ) { innerPadding ->
            when (selectedTab) {
                "subscriptions" -> SubscriptionsTab(
                    repository = libraryRepository,
                    accountEmail = googleAccountEmail,
                    refreshRevision = libraryRevision,
                    feedRefreshPreset = feedRefreshPreset,
                    youtubeSyncBusy = youtubeSyncBusy,
                    youtubeSyncMessage = youtubeSyncMessage,
                    onSyncYouTubeSubscriptions = onSyncYouTubeSubscriptions,
                    onAddChannel = onAddChannel,
                    onBrowseChannel = onBrowseChannel,
                    onPlay = onPlay,
                    modifier = Modifier.padding(innerPadding),
                )
                "profile" -> ProfileTab(
                    accountName = googleAccountName,
                    accountEmail = googleAccountEmail,
                    accountMessage = googleAccountMessage,
                    signInBusy = googleSignInBusy,
                    onSignIn = onGoogleSignIn,
                    onSignOut = onGoogleSignOut,
                    modifier = Modifier.padding(innerPadding),
                )
                else -> Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (selectedTab == "search") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused },
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchAction() }),
                )
                Button(onClick = searchAction, enabled = !state.loading) { Text(stringResource(R.string.search_action)) }
            }

            directPlaylistQueueItem?.let { playlist ->
                TextButton(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    onClick = { onAddToQueue(playlist) },
                ) {
                    Text(stringResource(R.string.add_playlist_to_queue_action))
                }
            }

            val matchingRecentSearches = if (query.isBlank()) recentSearches else recentSearches.filter {
                it.startsWith(query.trim(), ignoreCase = true)
            }
            if (searchFocused && matchingRecentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { searchHistory.clear(); recentSearches = emptyList() }) {
                        Text(stringResource(R.string.clear_recent_searches))
                    }
                }
                matchingRecentSearches.take(6).forEach { recent ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { query = recent; executeSearch(recent) },
                    ) {
                        Text("◷  $recent", modifier = Modifier.fillMaxWidth(), maxLines = 1)
                    }
                }
            }

            }

            if (selectedTab == "search" || selectedTab == "home") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val filters = if (selectedTab == "home") listOf(
                        "all" to R.string.filter_all,
                        "video" to R.string.filter_videos,
                        "shorts" to R.string.filter_shorts,
                        "live" to R.string.filter_live,
                    ) else if (channelBrowse != null) listOf(
                        "video" to R.string.filter_videos,
                        "shorts" to R.string.filter_shorts,
                        "live" to R.string.filter_live,
                        "playlist" to R.string.filter_playlists,
                    ) else listOf(
                        "all" to R.string.filter_all,
                        "video" to R.string.filter_videos,
                        "shorts" to R.string.filter_shorts,
                        "live" to R.string.filter_live,
                        "playlist" to R.string.filter_playlists,
                    )
                    filters.forEach { (value, label) ->
                        FilterChip(
                            selected = activeKind == value,
                            onClick = {
                                if (selectedTab == "home") {
                                    recommendationKind = value
                                } else if (channelBrowse != null) {
                                    val target = channelBrowseUrl(query, value)
                                    if (target != null) {
                                        searchKind = value
                                        query = target
                                        viewModel.search(target, language, emptySearchMessage, requestFailedMessage, pageSize)
                                    }
                                } else {
                                    searchKind = value
                                }
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.error != null && state.items.isEmpty()) {
                MessagePanel(state.error.orEmpty())
            } else if (visibleItems.isEmpty()) {
                MessagePanel(when {
                    state.isHome -> stringResource(R.string.recommendations_empty)
                    state.searched -> stringResource(R.string.search_empty)
                    else -> stringResource(R.string.search_start)
                })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(displayedItems, key = { it.kind + ":" + it.id }) { item ->
                        VideoCard(
                            item = item,
                            isFollowing = isFollowing(item.channelId),
                            onQueue = { onAddToQueue(item) },
                            onDownload = {
                                downloadRequestGeneration++
                                downloadOptions = null
                                downloadOptionsLoading = false
                                downloadOptionsError = null
                                downloadTarget = item
                            },
                            onSave = { saveTarget = item },
                            onToggleFollow = { onToggleFollow(item) },
                            onChannelClick = {
                                val channelUrl = Uri.Builder()
                                    .scheme("https")
                                    .authority("www.youtube.com")
                                    .appendPath("channel")
                                    .appendPath(item.channelId)
                                    .build()
                                onBrowseChannel(channelUrl.toString())
                            },
                            onShare = {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            createShareIntent(item),
                                            context.getString(R.string.share_link),
                                        ),
                                    )
                                }.onFailure { Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show() }
                            },
                            onClick = if (item.kind == "playlist") {
                            {
                                query = item.url
                                viewModel.search(item.url, language, emptySearchMessage, requestFailedMessage, pageSize)
                            }
                            } else ({
                                if (item.kind == "shorts") onPlayShorts(item, visibleItems) else onPlay(item)
                            }),
                        )
                    }
                    if (state.continuation != null || visibleItems.size > state.displayLimit) {
                        item(key = "load-more") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                if (state.error != null) Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                Button(onClick = { viewModel.loadMore(language, requestFailedMessage, pageSize, visibleItems.size) }, enabled = !state.loadingMore) {
                                    if (state.loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Text(stringResource(R.string.load_more))
                                }
                            }
                        }
                    }
                    if (state.error != null && state.continuation == null) item { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                }
            }
                }
        }
    }

    saveTarget?.let { video ->
        AlertDialog(
            onDismissRequest = { saveTarget = null },
            title = { Text(stringResource(R.string.save_to_playlist)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { saveTarget = null; onSaveVideo(video) }) {
                        Text(stringResource(R.string.watch_later))
                    }
                    if (localPlaylists.isEmpty()) {
                        Text(stringResource(R.string.no_local_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(localPlaylists, key = { it.id }) { playlist ->
                                TextButton(onClick = { saveTarget = null; onSaveToPlaylist(video, playlist.id) }) {
                                    Text(playlist.name)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { saveTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    downloadTarget?.takeIf { downloadOptions == null }?.let { video ->
        AlertDialog(
            onDismissRequest = dismissDownloadDialog,
            title = { Text(stringResource(R.string.download_options)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { requestDownloadOptions(video, false) }) {
                        Text(stringResource(R.string.download_video))
                    }
                    TextButton(onClick = { requestDownloadOptions(video, true) }) {
                        Text(stringResource(R.string.download_audio_only))
                    }
                }
                if (downloadOptionsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.download_loading_formats))
                    }
                }
                downloadOptionsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            },
            confirmButton = { TextButton(onClick = dismissDownloadDialog) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val availableDownloadOptions = downloadOptions
    val formatVideo = downloadTarget
    if (formatVideo != null && availableDownloadOptions != null) {
        DownloadFormatPickerDialog(
            options = availableDownloadOptions,
            audioOnly = downloadAudioOnly,
            onDismiss = dismissDownloadDialog,
            onSelect = { option ->
                dismissDownloadDialog()
                onDownload(formatVideo, option)
            },
        )
    }
}

@Composable
internal fun DownloadFormatPickerDialog(
    options: List<DownloadFormatOption>,
    audioOnly: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DownloadFormatOption) -> Unit,
) {
    val audioCodecFallback = stringResource(R.string.audio_codec_generic)
    val videoCodecFallback = stringResource(R.string.video_codec_generic)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (audioOnly) R.string.download_audio_only else R.string.download_video)) },
        text = {
            if (options.isEmpty()) {
                Text(stringResource(R.string.download_no_formats))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(options, key = { it.key }) { option ->
                        val stream = option.stream
                        val codec = if (option.audioOnly) stream.audioCodec else stream.videoCodec
                        val caption = if (option.audioOnly) {
                            stringResource(
                                R.string.download_audio_format,
                                codec.ifBlank { audioCodecFallback },
                                option.bitrateKbps.takeIf { it > 0 }?.toString() ?: "?",
                                option.container,
                            )
                        } else {
                            stringResource(
                                R.string.download_video_format,
                                stream.videoHeight,
                                stream.fps.roundToInt(),
                                codec.ifBlank { videoCodecFallback },
                                (option.audioStream?.audioCodec ?: stream.audioCodec).ifBlank { audioCodecFallback },
                                option.container,
                            )
                        }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onSelect(option) }) { Text(caption) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun VideoCard(
    item: VideoItem,
    isFollowing: Boolean,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onSave: () -> Unit,
    onToggleFollow: () -> Boolean,
    onChannelClick: () -> Unit,
    onShare: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnailUrl = VideoThumbnailPolicy.resolve(item)
    var followed by remember(item.channelId) { mutableStateOf(isFollowing) }
    var moreActionsExpanded by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isFollowing) { followed = isFollowing }
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.channel.isNotBlank()) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = item.channelId.isNotBlank(), onClick = onChannelClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.channelAvatarUrl.isNotBlank()) {
                            SubcomposeAsyncImage(
                                model = item.channelAvatarUrl,
                                contentDescription = item.channel,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                loading = { Text(item.channel.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall) },
                                error = { Text(item.channel.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall) },
                                success = { SubcomposeAsyncImageContent() },
                            )
                        } else {
                            Text(item.channel.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    if (item.channel.isNotBlank()) {
                        Text(
                            item.channel,
                            modifier = if (item.channelId.isNotBlank()) Modifier.clickable(onClick = onChannelClick) else Modifier,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    val details = listOf(item.duration.takeIf { it != "—" }, item.published.takeIf(String::isNotBlank), item.kind.takeIf { it != "video" })
                        .filterNotNull().joinToString(" · ")
                    if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Box {
                    IconButton(
                        onClick = { moreActionsExpanded = true },
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.more_actions) },
                    ) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = moreActionsExpanded,
                        onDismissRequest = { moreActionsExpanded = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_queue)) },
                            onClick = { moreActionsExpanded = false; onQueue() },
                        )
                        if (item.kind != "playlist") {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.download)) },
                                onClick = { moreActionsExpanded = false; onDownload() },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_for_later)) },
                                onClick = { moreActionsExpanded = false; onSave() },
                            )
                            if (item.channelId.isNotBlank()) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(if (followed) R.string.followed else R.string.follow)) },
                                    onClick = { moreActionsExpanded = false; followed = onToggleFollow() },
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_url)) },
                            onClick = {
                                moreActionsExpanded = false
                                val uri = Uri.parse(item.url)
                                if (isTrustedYouTubeUri(uri)) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                        .onFailure { Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show() }
                                } else {
                                    Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_url)) },
                            onClick = {
                                moreActionsExpanded = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(item.title, item.url))
                                Toast.makeText(context, R.string.url_copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_link)) },
                            onClick = { moreActionsExpanded = false; onShare() },
                        )
                    }
                }
            }
            if (item.kind == "playlist") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onQueue) {
                        Text(stringResource(R.string.add_playlist_to_queue_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagePanel(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun currentLanguage(): String {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = if (Build.VERSION.SDK_INT >= 24) configuration.locales[0] else {
        @Suppress("DEPRECATION")
        configuration.locale
    }
    return locale.language.takeIf { it in setOf("en", "uk", "ru") } ?: "en"
}

internal fun createShareIntent(item: VideoItem): Intent {
    val uri = Uri.parse(item.url)
    require(isTrustedYouTubeUri(uri)) { "Only HTTPS YouTube links can be shared." }
    val content = if (item.title.isBlank()) item.url else "${item.title}\n${item.url}"
    return Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, content)
}

private fun isTrustedYouTubeUri(uri: Uri): Boolean {
    val host = uri.host?.lowercase(java.util.Locale.ROOT)
    return uri.scheme == "https" && host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
}

internal fun youtubePlaylistQueueMarker(input: String, title: String): VideoItem? {
    val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(java.util.Locale.ROOT)
    if (uri.scheme != "https" || host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")) return null
    if (uri.userInfo != null || (uri.port != -1 && uri.port != 443) || uri.path != "/playlist") return null
    val ids = uri.getQueryParameters("list")
    if (ids.size != 1) return null
    val id = ids.single().takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,256}")) } ?: return null
    return VideoItem(
        id = id,
        title = title,
        kind = "playlist",
        url = "https://www.youtube.com/playlist?list=$id",
    )
}

internal data class ChannelBrowseTab(val baseUrl: String, val kind: String)

internal fun channelBrowseTab(input: String): ChannelBrowseTab? {
    val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
    if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")) return null
    if (uri.scheme != "https" && uri.scheme != "http") return null
    val segments = uri.pathSegments
    val rootCount = when (segments.firstOrNull()) {
        "channel" -> if (segments.getOrNull(1)?.matches(Regex("UC[A-Za-z0-9_-]{22}")) == true) 2 else return null
        "c", "user" -> if (segments.getOrNull(1)?.matches(Regex("[A-Za-z0-9._-]{1,100}")) == true) 2 else return null
        else -> if (segments.firstOrNull()?.matches(Regex("@[A-Za-z0-9._-]{3,30}")) == true) 1 else return null
    }
    val kind = when (segments.getOrNull(rootCount)) {
        "shorts" -> "shorts"
        "streams" -> "live"
        "playlists" -> "playlist"
        else -> "video"
    }
    val basePath = segments.take(rootCount).joinToString("/")
    return ChannelBrowseTab("https://www.youtube.com/$basePath", kind)
}

internal fun channelBrowseUrl(input: String, requestedKind: String? = null): String? {
    val current = channelBrowseTab(input) ?: return null
    val suffix = when (requestedKind ?: current.kind) {
        "all", "video" -> "videos"
        "shorts" -> "shorts"
        "live" -> "streams"
        "playlist" -> "playlists"
        else -> return null
    }
    return "${current.baseUrl}/$suffix"
}

internal object FeedAutoPaginationPolicy {
    private const val PREFETCH_DISTANCE = 4

    fun shouldPrefetch(lastVisibleIndex: Int, itemCount: Int): Boolean =
        itemCount > 0 && lastVisibleIndex >= 0 && lastVisibleIndex >= (itemCount - PREFETCH_DISTANCE).coerceAtLeast(0)
}
