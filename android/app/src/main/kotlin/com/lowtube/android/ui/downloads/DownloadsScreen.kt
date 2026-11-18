package com.lowtube.android.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(repository: DownloadRepository, onBack: () -> Unit, onRetry: (Long, () -> Unit) -> Unit, onOpen: (Long) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var retryingIds by remember { mutableStateOf(emptySet<Long>()) }
    val records = remember(refresh) { repository.records() }
    LaunchedEffect(Unit) {
        repository.resumePending()
    }
    val hasActiveDownloads = records.any { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.RUNNING }
    LaunchedEffect(hasActiveDownloads) {
        if (hasActiveDownloads) {
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
        if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.downloads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    DownloadRow(
                        record,
                        onRemove = {
                            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) repository.cancel(record.id)
                            else repository.remove(record.id)
                            refresh++
                        },
                        retrying = record.id in retryingIds,
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
private fun DownloadRow(record: DownloadRecord, retrying: Boolean, onOpen: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit) {
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) {
                if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (record.totalBytes > 0) {
                    Text("${formatBytes(record.downloadedBytes)} / ${formatBytes(record.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (record.status == DownloadStatus.FAILED) {
                Text(stringResource(R.string.download_error_code, record.reason), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (record.status == DownloadStatus.SUCCESSFUL) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.open_download)) }
                }
                if (record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED || record.status == DownloadStatus.PAUSED) {
                    TextButton(onClick = onRetry, enabled = !retrying) { Text(stringResource(R.string.retry)) }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(if (record.status == DownloadStatus.SUCCESSFUL || record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED) R.string.remove else R.string.cancel_download))
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib < 1024.0) String.format(java.util.Locale.ROOT, "%.1f MiB", mib)
    else String.format(java.util.Locale.ROOT, "%.2f GiB", mib / 1024.0)
}
