package com.lowtube.android.ui.queue

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.QueueEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    repository: LocalLibraryRepository,
    refreshRevision: Int,
    onBack: () -> Unit,
    onPlay: (QueueEntry) -> Unit,
    onExpandPlaylist: (QueueEntry) -> Unit,
    expandingPlaylistRowId: Long?,
    autoNext: Boolean,
    onAutoNextChanged: (Boolean) -> Unit,
) {
    var refresh by remember(refreshRevision) { mutableIntStateOf(refreshRevision) }
    val entries = remember(refresh) { repository.queue() }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watch_queue)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_queue)) } },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.automatically_play_next), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = autoNext, onCheckedChange = onAutoNextChanged)
                }
                Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "auto-next") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.automatically_play_next), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = autoNext, onCheckedChange = onAutoNextChanged)
                    }
                }
                items(entries, key = { it.rowId }) { entry ->
                    QueueRow(
                        entry = entry,
                        onPlay = { onPlay(entry) },
                        onExpandPlaylist = { onExpandPlaylist(entry) },
                        expanding = expandingPlaylistRowId == entry.rowId,
                        onMoveUp = { repository.moveQueueEntry(entry.rowId, -1); refresh++ },
                        onMoveDown = { repository.moveQueueEntry(entry.rowId, 1); refresh++ },
                        onRemove = { repository.removeQueueEntry(entry.rowId); refresh++ },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_queue)) },
            text = { Text(stringResource(R.string.clear_queue_confirm)) },
            confirmButton = {
                Button(onClick = { repository.clearQueue(); confirmClear = false; refresh++ }) { Text(stringResource(R.string.clear_queue)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    onPlay: () -> Unit,
    onExpandPlaylist: () -> Unit,
    expanding: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(entry.video.title, style = MaterialTheme.typography.titleMedium)
                val detail = if (entry.video.kind == "playlist") stringResource(R.string.playlist) else entry.video.channel
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                if (entry.video.kind != "playlist") TextButton(onClick = onPlay) { Text(stringResource(R.string.play)) }
                else TextButton(onClick = onExpandPlaylist, enabled = !expanding) {
                    Text(stringResource(if (expanding) R.string.expanding_playlist else R.string.load_playlist_page))
                }
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}
