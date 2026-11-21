package com.lowtube.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.LocalSubscriptionFeedRepository
import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val SUBSCRIPTIONS_FEED_GRID_TEST_TAG = "subscriptions-adaptive-grid"
internal const val PROFILE_TWO_PANE_TEST_TAG = "profile-two-pane"
internal const val PROFILE_ACCOUNT_PANE_TEST_TAG = "profile-account-pane"
internal const val PROFILE_HISTORY_PANE_TEST_TAG = "profile-history-pane"

@Composable
internal fun SubscriptionsTab(
    repository: LocalLibraryRepository,
    accountEmail: String,
    refreshRevision: Int,
    feedRefreshPreset: Int = 1,
    youtubeSyncBusy: Boolean,
    youtubeSyncMessage: String,
    onSyncYouTubeSubscriptions: () -> Unit,
    onAddChannel: (String) -> Unit,
    onBrowseChannel: (String) -> Unit,
    onPlay: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedRepository = remember(repository, context) {
        LocalSubscriptionFeedRepository(repository, InnerTubeSearchRepository(context.applicationContext))
    }
    var localRevision by remember { mutableIntStateOf(0) }
    val revision = refreshRevision + localRevision
    val channels = remember(repository, revision, accountEmail) { repository.channels(accountEmail) }
    val channelIds = remember(channels) { channels.mapTo(HashSet()) { it.id } }
    val categories = remember(channels) { channels.map { it.category.trim() }.filter(String::isNotEmpty).distinct().sortedBy(String::lowercase) }
    val categoryByChannel = remember(channels) { channels.associate { it.id to it.category.trim() } }
    val videos = remember(feedRepository, revision, channelIds) { feedRepository.cached().filter { it.channelId in channelIds } }
    var selectedChannel by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf("all") }
    var addChannelDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var categoryTarget by remember { mutableStateOf<SavedChannel?>(null) }
    var categoryInput by remember { mutableStateOf("") }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var refreshFailed by remember { mutableStateOf(false) }
    val visibleVideos = remember(videos, selectedChannel, selectedCategory, selectedKind, categoryByChannel) {
        videos.filter {
            (selectedChannel.isBlank() || it.channelId == selectedChannel) &&
                (selectedCategory.isBlank() || categoryByChannel[it.channelId] == selectedCategory) &&
                (selectedKind == "all" || it.kind == selectedKind)
        }
    }
    fun saveChannelCategory(channel: SavedChannel, category: String) {
        runCatching { repository.setChannelCategory(channel.id, category, accountEmail) }
            .onSuccess {
                localRevision++
                selectedCategory = ""
                categoryTarget = null
                Toast.makeText(context, R.string.channel_category_saved, Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.action_failed), Toast.LENGTH_LONG).show() }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.subscription_feed), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { channelInput = ""; addChannelDialog = true }) {
                Text(stringResource(R.string.add_channel))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = accountEmail.isNotBlank() && !youtubeSyncBusy,
                onClick = onSyncYouTubeSubscriptions,
            ) {
                if (youtubeSyncBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.sync_youtube_subscriptions))
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = refreshJob?.isActive != true && channels.isNotEmpty(),
                onClick = {
                    refreshMessage = null
                    refreshFailed = false
                    refreshJob = scope.launch {
                        try {
                            val result = feedRepository.refreshBatch(AppLocale.language(context), feedRefreshPreset, accountEmail)
                            refreshMessage = context.getString(
                                R.string.feed_refresh_summary,
                                result.refreshedChannels,
                                result.failedChannels.size,
                                result.remainingChannels,
                                result.browseFallbackChannels,
                            )
                            localRevision++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            refreshFailed = true
                        } finally {
                            refreshJob = null
                        }
                    }
                },
            ) {
                if (refreshJob?.isActive == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.refresh_feed))
            }
            if (refreshJob?.isActive == true) {
                TextButton(onClick = { refreshJob?.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        }
        if (accountEmail.isBlank()) {
            Text(stringResource(R.string.youtube_account_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (youtubeSyncMessage.isNotBlank()) {
            Text(youtubeSyncMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        refreshMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (refreshFailed) Text(stringResource(R.string.feed_refresh_failed), color = MaterialTheme.colorScheme.error)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedChannel.isBlank(),
                onClick = { selectedChannel = "" },
                label = { Text(stringResource(R.string.all_channels)) },
            )
            channels.forEach { channel ->
                FilterChip(
                    selected = selectedChannel == channel.id,
                    onClick = { selectedChannel = if (selectedChannel == channel.id) "" else channel.id },
                    label = { Text(channel.title.ifBlank { channel.id }, maxLines = 1) },
                )
            }
        }
        val selectedCategoryChannel = channels.firstOrNull { it.id == selectedChannel }
        if (selectedCategoryChannel != null) {
            TextButton(onClick = {
                categoryInput = selectedCategoryChannel.category
                categoryTarget = selectedCategoryChannel
            }) { Text(stringResource(R.string.edit_channel_category)) }
        }
        if (categories.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategory.isBlank(),
                    onClick = { selectedCategory = "" },
                    label = { Text(stringResource(R.string.all_categories)) },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = if (selectedCategory == category) "" else category },
                        label = { Text(category, maxLines = 1) },
                    )
                }
            }
        }
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
                    selected = selectedKind == kind,
                    onClick = { selectedKind = kind },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        if (channels.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.subscriptions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { channelInput = ""; addChannelDialog = true }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.add_channel))
                }
            }
        } else if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.subscription_feed_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (visibleVideos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.subscription_feed_filtered_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                modifier = Modifier.fillMaxSize().testTag(SUBSCRIPTIONS_FEED_GRID_TEST_TAG),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(visibleVideos, key = { it.channelId + ":" + it.id }) { video ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onPlay(video) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column {
                            if (video.thumbnailUrl.isNotBlank()) {
                                AsyncImage(
                                    model = video.thumbnailUrl,
                                    contentDescription = video.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(Modifier.padding(12.dp)) {
                                Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(video.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onBrowseChannel("https://www.youtube.com/channel/${video.channelId}") }) {
                                        Text(stringResource(R.string.browse_channel))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addChannelDialog) {
        AlertDialog(
            onDismissRequest = { addChannelDialog = false },
            title = { Text(stringResource(R.string.add_channel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.add_channel_help))
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        label = { Text(stringResource(R.string.channel_url_or_handle)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = channelInput.isNotBlank(),
                    onClick = { val input = channelInput.trim(); addChannelDialog = false; onAddChannel(input) },
                ) { Text(stringResource(R.string.add_channel)) }
            },
            dismissButton = { TextButton(onClick = { addChannelDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    categoryTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { categoryTarget = null },
            title = { Text(stringResource(R.string.category_for_channel, target.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.channel_category_hint))
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it.take(64) },
                        label = { Text(stringResource(R.string.category_name)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveChannelCategory(target, categoryInput)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { saveChannelCategory(target, "") }) { Text(stringResource(R.string.remove_category)) }
                    TextButton(onClick = { categoryTarget = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
internal fun ProfileTab(
    accountName: String,
    accountEmail: String,
    accountAvatarUrl: String = "",
    accountMessage: String,
    signInBusy: Boolean,
    youtubeHistoryItems: List<VideoItem> = emptyList(),
    youtubeHistoryBusy: Boolean = false,
    youtubeHistoryMessage: String = "",
    youtubeHistoryHasMore: Boolean = false,
    onLoadYouTubeHistory: (Boolean) -> Unit = {},
    onPlayHistoryVideo: (VideoItem) -> Unit = {},
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accountCard: @Composable () -> Unit = {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.google_account), style = MaterialTheme.typography.titleMedium)
                    if (accountEmail.isBlank()) {
                        Text(stringResource(R.string.account_guest), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.account_sign_in_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(accountName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: accountEmail.firstOrNull()?.uppercaseChar()?.toString().orEmpty())
                                if (accountAvatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = accountAvatarUrl,
                                        contentDescription = accountName.ifBlank { accountEmail },
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Column {
                                Text(accountName.ifBlank { accountEmail }, style = MaterialTheme.typography.bodyLarge)
                                if (accountName.isNotBlank()) Text(accountEmail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (accountMessage.isNotBlank()) {
                        Text(accountMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.google_account), accountMessage))
                            Toast.makeText(context, R.string.account_message_copied, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy_account_message)) }
                    }
                    Button(enabled = !signInBusy, onClick = onSignIn) {
                        if (signInBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(if (accountEmail.isBlank()) R.string.account_sign_in else R.string.account_switch))
                    }
                    if (accountEmail.isNotBlank()) TextButton(onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
                }
            }
    }
    val historyCard: @Composable () -> Unit = {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.youtube_history_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.youtube_history_experimental), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (accountEmail.isBlank()) {
                        Text(stringResource(R.string.youtube_history_sign_in_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = accountEmail.isNotBlank() && !youtubeHistoryBusy, onClick = { onLoadYouTubeHistory(true) }) {
                            if (youtubeHistoryBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.youtube_history_refresh))
                        }
                        if (youtubeHistoryHasMore) {
                            TextButton(enabled = !youtubeHistoryBusy, onClick = { onLoadYouTubeHistory(false) }) {
                                Text(stringResource(R.string.load_more))
                            }
                        }
                    }
                    if (youtubeHistoryMessage.isNotBlank()) {
                        Text(youtubeHistoryMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (youtubeHistoryItems.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(youtubeHistoryItems, key = { "youtube-history:${it.kind}:${it.id}" }) { video ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = video.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                        if (video.channel.isNotBlank()) Text(video.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(video.published, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { onPlayHistoryVideo(video) }) { Text(stringResource(R.string.play)) }
                                }
                            }
                        }
                    }
                }
            }
    }
    val supportedLinksCard: @Composable () -> Unit = {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.supported_links), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.supported_links_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = {
                            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                            } else {
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            }
                            val intent = Intent(action, Uri.fromParts("package", context.packageName, null))
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show() }
                        },
                    ) { Text(stringResource(R.string.open_link_settings)) }
                }
            }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 800.dp) {
            Row(
                Modifier.width(maxWidth.coerceAtMost(1280.dp))
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .testTag(PROFILE_TWO_PANE_TEST_TAG),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .testTag(PROFILE_ACCOUNT_PANE_TEST_TAG),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    accountCard()
                    supportedLinksCard()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .testTag(PROFILE_HISTORY_PANE_TEST_TAG),
                ) {
                    historyCard()
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
                    .align(Alignment.TopCenter).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { accountCard() }
                item { historyCard() }
                item { supportedLinksCard() }
            }
        }
    }
}
