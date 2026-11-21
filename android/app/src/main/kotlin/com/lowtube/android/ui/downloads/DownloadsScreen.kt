package com.lowtube.android.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DOWNLOADS_GRID_TEST_TAG = "downloads-adaptive-grid"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    repository: DownloadRepository,
    onBack: () -> Unit,
    onRetry: (Long, () -> Unit) -> Unit,
    onPlayInLowTube: (Long) -> Unit = {},
    onOpen: (Long) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var retryingIds by remember { mutableStateOf(emptySet<Long>()) }
    var loadFailed by remember(repository) { mutableStateOf(false) }
    val loadedRecords by produceState<List<DownloadRecord>?>(null, repository, refresh) {
        try {
            value = withContext(Dispatchers.IO) { repository.records() }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val records = loadedRecords.orEmpty()
    LaunchedEffect(Unit) {
        repository.resumePending()
    }
    val hasActiveDownloads = records.any { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING }
    LaunchedEffect(hasActiveDownloads, loadFailed) {
        if (hasActiveDownloads && !loadFailed) {
            while (true) {
                delay(1_500)
                refresh++
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.refresh)) } },
            )
        },
    ) { padding ->
        if (loadFailed) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.downloads_read_failed), color = MaterialTheme.colorScheme.error)
                Button(onClick = { refresh++ }) { Text(stringResource(R.string.retry)) }
            }
        } else if (loadedRecords == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
        } else if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.downloads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag(DOWNLOADS_GRID_TEST_TAG),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    DownloadRow(
                        record,
                        modifier = Modifier.testTag("download-entry-${record.id}"),
                        onRemove = {
                            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) repository.cancel(record.id)
                            else repository.remove(record.id)
                            refresh++
                        },
                        retrying = record.id in retryingIds,
                        onPlayInLowTube = { onPlayInLowTube(record.id) },
                        onOpen = { onOpen(record.id) },
                        onRetry = {
                            if (record.id !in retryingIds) {
                                retryingIds = retryingIds + record.id
                                onRetry(record.id) {
                                    retryingIds = retryingIds - record.id
                                    refresh++
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadRow(
    record: DownloadRecord,
    retrying: Boolean,
    modifier: Modifier = Modifier,
    onPlayInLowTube: () -> Unit = {},
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = when (record.status) {
        DownloadStatus.PENDING -> stringResource(R.string.download_pending)
        DownloadStatus.RUNNING -> stringResource(R.string.download_running)
        DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
        DownloadStatus.SUCCESSFUL -> stringResource(R.string.download_complete)
        else -> stringResource(R.string.download_failed)
    }
    val format = when (record.mimeType) {
        "video/mp4" -> stringResource(R.string.download_format_video_mp4)
        "video/webm" -> stringResource(R.string.download_format_video_webm)
        "audio/mp4" -> stringResource(R.string.download_format_audio_m4a)
        "audio/webm" -> stringResource(R.string.download_format_audio_webm)
        "audio/mpeg" -> stringResource(R.string.download_format_audio_mp3)
        else -> record.mimeType
    }
    val progress = if (record.totalBytes > 0) record.downloadedBytes.toFloat() / record.totalBytes.toFloat() else null
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress != null && record.status != DownloadStatus.SUCCESSFUL) {
                val boundedProgress = progress.coerceIn(0f, 1f)
                val percentage = (boundedProgress * 100).toInt()
                LinearProgressIndicator(progress = { boundedProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "$percentage% • ${formatBytes(record.downloadedBytes)} / ${formatBytes(record.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (record.status == DownloadStatus.FAILED) {
                Text(stringResource(R.string.download_error_code, record.reason), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (record.status == DownloadStatus.SUCCESSFUL) {
                    if (record.mimeType.startsWith("video/") || record.mimeType.startsWith("audio/")) {
                        TextButton(onClick = onPlayInLowTube) { Text(stringResource(R.string.play_download_in_lowtube)) }
                    }
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.open_download)) }
                }
                if (record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED || record.status == DownloadStatus.PAUSED) {
                    TextButton(onClick = onRetry, enabled = !retrying) { Text(stringResource(R.string.retry)) }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(if (record.status == DownloadStatus.SUCCESSFUL || record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED || record.status == DownloadStatus.PAUSED) R.string.remove else R.string.cancel_download))
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024L) return "$safeBytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var scaled = safeBytes.toDouble()
    var unitIndex = -1
    do {
        scaled /= 1024.0
        unitIndex++
    } while (scaled >= 1024.0 && unitIndex < units.lastIndex)
    return String.format(java.util.Locale.ROOT, "%.1f %s", scaled, units[unitIndex])
}
