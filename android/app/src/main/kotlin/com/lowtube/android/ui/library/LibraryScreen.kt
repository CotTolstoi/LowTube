package com.lowtube.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalSubscriptionFeedRepository
import com.lowtube.android.data.SubscriptionFeedRefresh
import com.lowtube.android.AppLocale
import com.lowtube.android.model.LocalPlaylist
import com.lowtube.android.model.LocalAudioTrack
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.WatchHistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: LocalLibraryRepository,
    accountEmail: String,
    refreshRevision: Int,
    onBack: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onPlayPlaylist: (List<VideoItem>) -> Unit,
    onPickAudio: () -> Unit,
    onPlayLocalAudio: (LocalAudioTrack) -> Unit,
    onRemoveLocalAudio: (LocalAudioTrack) -> Unit,
    onAddToQueue: (List<VideoItem>) -> Unit,
    onBrowseChannel: (String) -> Unit,
    onAddChannel: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedRepository = remember(repository, context) { LocalSubscriptionFeedRepository(repository, InnerTubeSearchRepository(context.applicationContext)) }
    var refresh by remember(refreshRevision) { mutableIntStateOf(refreshRevision) }
    val playlists = remember(refresh) { repository.playlists() }
    val channels = remember(refresh, accountEmail) { repository.channels(accountEmail) }
    val channelIds = remember(channels) { channels.mapTo(HashSet()) { it.id } }
    val history = remember(refresh) { repository.watchHistory() }
    val localAudioTracks = remember(refresh) { repository.localAudioTracks() }
    val subscriptionVideos = remember(refresh, channelIds) { feedRepository.cached().filter { it.channelId in channelIds } }
    var selectedFeedChannelId by rememberSaveable { mutableStateOf("") }
    var selectedFeedKind by rememberSaveable { mutableStateOf("all") }
    var feedChannelMenuExpanded by remember { mutableStateOf(false) }
    val selectedFeedChannel = channels.firstOrNull { it.id == selectedFeedChannelId }
    val visibleSubscriptionVideos = remember(subscriptionVideos, selectedFeedChannelId, selectedFeedKind) {
        subscriptionVideos.filter {
            (selectedFeedChannelId.isBlank() || it.channelId == selectedFeedChannelId) &&
                (selectedFeedKind == "all" || it.kind == selectedFeedKind)
        }
    }
    var feedJob by remember { mutableStateOf<Job?>(null) }
    var feedRefreshResult by remember { mutableStateOf<SubscriptionFeedRefresh?>(null) }
    var feedRefreshFailed by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = playlists.firstOrNull { it.id == selectedId }
    val videos = remember(refresh, selectedId) { selectedId?.let(repository::videos).orEmpty() }
    var editTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var editName by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddChannelDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var confirmHistoryClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.library)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            actions = { TextButton(onClick = { editTarget = null; editName = ""; error = null; showEditDialog = true }) { Text(stringResource(R.string.new_playlist)) } },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.local_audio), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onPickAudio) { Text(stringResource(R.string.add_audio_files)) }
                }
            }
            if (localAudioTracks.isEmpty()) item {
                Text(stringResource(R.string.local_audio_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(localAudioTracks, key = { "local-audio:${it.uri}" }) { track ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onPlayLocalAudio(track) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(track.mimeType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRemoveLocalAudio(track) }) { Text(stringResource(R.string.remove_audio)) }
                    }
                }
            }
            item { Text(stringResource(R.string.local_playlists), style = MaterialTheme.typography.titleLarge) }
            if (playlists.isEmpty()) item { Text(stringResource(R.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(playlists, key = { "playlist:${it.id}" }) { playlist ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedId = playlist.id },
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (playlist.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                        if (playlist.id == selectedId) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editTarget = playlist; editName = playlist.name; error = null; showEditDialog = true }) { Text(stringResource(R.string.rename)) }
                                TextButton(onClick = { deleteTarget = playlist }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }

            selected?.let { playlist ->
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                        if (videos.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onPlayPlaylist(videos) }) { Text(stringResource(R.string.play_all)) }
                                TextButton(onClick = { onAddToQueue(videos) }) { Text(stringResource(R.string.add_playlist_to_queue)) }
                            }
                        }
                    }
                }
                if (videos.isEmpty()) item { Text(stringResource(R.string.playlist_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(videos.indices.toList(), key = { "${playlist.id}:$it:${videos[it].id}" }) { index ->
                    val video = videos[index]
                    LibraryVideoRow(
                        video = video,
                        onPlay = { onPlay(video) },
                        onMoveUp = { if (index > 0) { repository.moveVideo(playlist.id, index, index - 1); refresh++ } },
                        onMoveDown = { if (index < videos.lastIndex) { repository.moveVideo(playlist.id, index, index + 1); refresh++ } },
                        onRemove = { repository.removeVideo(playlist.id, index); refresh++ },
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.local_subscriptions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { channelInput = ""; showAddChannelDialog = true }) { Text(stringResource(R.string.add_channel)) }
                }
            }
            if (channels.isEmpty()) item { Text(stringResource(R.string.subscriptions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(channels, key = { "channel:${it.id}" }) { channel ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(channel.title.ifBlank { channel.id }, style = MaterialTheme.typography.titleMedium)
                            Text(channel.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onBrowseChannel(channel.url) }) { Text(stringResource(R.string.browse_channel)) }
                        if (channel.fromYouTubeAccount) {
                            Text(stringResource(R.string.youtube_account_subscription), style = MaterialTheme.typography.bodySmall)
                        } else {
                            TextButton(onClick = {
                                repository.unfollow(channel.id)
                                if (selectedFeedChannelId == channel.id) selectedFeedChannelId = ""
                                refresh++
                            }) { Text(stringResource(R.string.unfollow)) }
                        }
                    }
                }
            }
            if (channels.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.subscription_feed), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = true,
                            onClick = {
                                if (feedJob?.isActive == true) feedJob?.cancel()
                                else {
                                    feedRefreshResult = null
                                    feedRefreshFailed = false
                                    feedJob = scope.launch {
                                        try {
                                            feedRefreshResult = feedRepository.refreshBatch(AppLocale.language(context))
                                            refresh++
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            feedRefreshFailed = true
                                        } finally {
                                            feedJob = null
                                        }
                                    }
                                }
                            },
                        ) { Text(stringResource(if (feedJob?.isActive == true) R.string.cancel else R.string.refresh_feed)) }
                    }
                }
                item {
                    Box {
                        TextButton(onClick = { feedChannelMenuExpanded = true }) {
                            Text(stringResource(R.string.channel_filter, selectedFeedChannel?.title ?: stringResource(R.string.all_channels)))
                        }
                        DropdownMenu(expanded = feedChannelMenuExpanded, onDismissRequest = { feedChannelMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.all_channels)) },
                                onClick = { selectedFeedChannelId = ""; feedChannelMenuExpanded = false },
                            )
                            channels.forEach { channel ->
                                DropdownMenuItem(
                                    text = { Text(channel.title.ifBlank { channel.id }) },
                                    onClick = { selectedFeedChannelId = channel.id; feedChannelMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "all" to R.string.filter_all,
                            "video" to R.string.filter_videos,
                            "shorts" to R.string.filter_shorts,
                            "live" to R.string.filter_live,
                        ).forEach { (kind, label) ->
                            FilterChip(
                                selected = selectedFeedKind == kind,
                                onClick = { selectedFeedKind = kind },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
                if (feedJob?.isActive == true) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (feedRefreshFailed) item { Text(stringResource(R.string.feed_refresh_failed), color = MaterialTheme.colorScheme.error) }
                feedRefreshResult?.let { result ->
                    item {
                        Text(
                            stringResource(R.string.feed_refresh_summary, result.refreshedChannels, result.failedChannels.size, result.remainingChannels, result.browseFallbackChannels),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result.failureDetails.firstOrNull()?.let { detail ->
                        item { Text(stringResource(R.string.feed_failure_detail, detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (visibleSubscriptionVideos.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (selectedFeedChannelId.isNotBlank() || selectedFeedKind != "all") R.string.subscription_feed_filtered_empty
                                else R.string.subscription_feed_empty,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(visibleSubscriptionVideos.take(30), key = { "subscription-feed:${it.id}" }) { video ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(video.title, style = MaterialTheme.typography.titleSmall)
                                if (video.channel.isNotBlank()) Text(video.channel, style = MaterialTheme.typography.bodySmall)
                                if (video.published.isNotBlank()) Text(video.published, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column {
                                TextButton(onClick = { onPlay(video) }) { Text(stringResource(R.string.play)) }
                                TextButton(onClick = { onAddToQueue(listOf(video)) }) { Text(stringResource(R.string.add_to_queue)) }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.watch_history), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    if (history.isNotEmpty()) TextButton(onClick = { confirmHistoryClear = true }) { Text(stringResource(R.string.clear_history)) }
                }
            }
            if (history.isEmpty()) item { Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history, key = { "history:${it.video.id}" }) { entry ->
                WatchHistoryRow(entry, onPlay = { onPlay(entry.video) }) {
                    repository.clearWatchedProgress(entry.video.id)
                    refresh++
                }
            }
            if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(if (editTarget == null) R.string.new_playlist else R.string.rename_playlist)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it; error = null }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true)
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        if (editTarget == null) selectedId = repository.createPlaylist(editName)
                        else repository.renamePlaylist(editTarget!!.id, editName)
                        editTarget = null
                        showEditDialog = false
                        error = null
                        refresh++
                    } catch (failure: Exception) { error = failure.message ?: "Could not save playlist." }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editTarget = null; showEditDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showAddChannelDialog) {
        AlertDialog(
            onDismissRequest = { showAddChannelDialog = false },
            title = { Text(stringResource(R.string.add_channel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_channel_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it.take(2048) },
                        label = { Text(stringResource(R.string.channel_url_or_handle)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val value = channelInput.trim()
                    if (value.isNotEmpty()) {
                        showAddChannelDialog = false
                        onAddChannel(value)
                    }
                }, enabled = channelInput.isNotBlank()) { Text(stringResource(R.string.add_channel)) }
            },
            dismissButton = { TextButton(onClick = { showAddChannelDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.delete_playlist_confirm, target.name)) },
            confirmButton = {
                Button(onClick = {
                    repository.deletePlaylist(target.id)
                    if (selectedId == target.id) selectedId = null
                    deleteTarget = null
                    refresh++
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmHistoryClear) {
        AlertDialog(
            onDismissRequest = { confirmHistoryClear = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.clear_history_confirm)) },
            confirmButton = {
                Button(onClick = { repository.clearWatchHistory(); confirmHistoryClear = false; refresh++ }) { Text(stringResource(R.string.clear_history)) }
            },
            dismissButton = { TextButton(onClick = { confirmHistoryClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LibraryVideoRow(video: VideoItem, onPlay: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall)
                if (video.channel.isNotBlank()) Text(video.channel, style = MaterialTheme.typography.bodySmall)
            }
            Column {
                TextButton(onClick = onPlay) { Text(stringResource(R.string.play)) }
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}

@Composable
private fun WatchHistoryRow(entry: WatchHistoryEntry, onPlay: () -> Unit, onRemove: () -> Unit) {
    val progressText = when {
        entry.complete -> stringResource(R.string.watched)
        entry.durationMs > 0 -> "${(entry.positionMs * 100 / entry.durationMs).coerceIn(0, 99)}% · ${entry.video.duration}"
        else -> "${entry.positionMs / 1000}s"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(entry.video.title, style = MaterialTheme.typography.titleSmall)
                if (entry.video.channel.isNotBlank()) Text(entry.video.channel, style = MaterialTheme.typography.bodySmall)
                Text(progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
    }
}
