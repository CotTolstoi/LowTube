package com.lowtube.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.LocalSubscriptionFeedRepository
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SubscriptionsTab(
    repository: LocalLibraryRepository,
    accountEmail: String,
    refreshRevision: Int,
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
    val videos = remember(feedRepository, revision, channelIds) { feedRepository.cached().filter { it.channelId in channelIds } }
    var selectedChannel by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf("all") }
    var addChannelDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var refreshFailed by remember { mutableStateOf(false) }
    val visibleVideos = remember(videos, selectedChannel, selectedKind) {
        videos.filter {
            (selectedChannel.isBlank() || it.channelId == selectedChannel) &&
                (selectedKind == "all" || it.kind == selectedKind)
        }
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
                            val result = feedRepository.refreshBatch(AppLocale.language(context))
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
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
}

@Composable
internal fun ProfileTab(
    accountName: String,
    accountEmail: String,
    accountMessage: String,
    signInBusy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.google_account), style = MaterialTheme.typography.titleMedium)
                if (accountEmail.isBlank()) {
                    Text(stringResource(R.string.account_guest), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.account_sign_in_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(accountName.ifBlank { accountEmail }, style = MaterialTheme.typography.bodyLarge)
                    if (accountName.isNotBlank()) Text(accountEmail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (accountMessage.isNotBlank()) {
                    Text(accountMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(enabled = !signInBusy, onClick = onSignIn) {
                    if (signInBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(if (accountEmail.isBlank()) R.string.account_sign_in else R.string.account_switch))
                }
                if (accountEmail.isNotBlank()) {
                    TextButton(onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
                }
            }
        }
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
}
