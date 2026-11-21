package com.lowtube.android.ui.playback

import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.lowtube.android.R
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackService
import kotlinx.coroutines.delay

/** Compact controls shown above the main navigation while the full player is in the back stack. */
@Composable
internal fun MiniPlayerBar(video: VideoItem, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackProgress by remember { mutableFloatStateOf(0f) }
    val controllerFuture = remember(context) {
        MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            { runCatching { controllerFuture.get() }.onSuccess { controller = it } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }

    DisposableEffect(controller) {
        val activeController = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isPlaying = activeController.isPlaying
            }
        }
        activeController.addListener(listener)
        isPlaying = activeController.isPlaying
        onDispose { activeController.removeListener(listener) }
    }

    androidx.compose.runtime.LaunchedEffect(controller, isPlaying) {
        val activeController = controller ?: return@LaunchedEffect
        while (isPlaying) {
            val duration = activeController.duration
            playbackProgress = if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET) {
                (activeController.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            delay(PROGRESS_UPDATE_INTERVAL_MS)
        }
        val duration = activeController.duration
        playbackProgress = if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET) {
            (activeController.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    Card(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 76.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)),
                )
                Row(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpenPlayer).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        video.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    enabled = controller != null,
                ) {
                    Text(stringResource(if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play))
                }
                TextButton(onClick = onOpenPlayer) { Text(stringResource(R.string.mini_player_open)) }
            }
            LinearProgressIndicator(
                progress = { playbackProgress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
