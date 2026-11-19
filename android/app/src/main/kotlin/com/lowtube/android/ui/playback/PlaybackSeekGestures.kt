package com.lowtube.android.ui.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.lowtube.android.R

private const val SEEK_OFFSET_MS = 10_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackSeekGestureOverlay(
    onShowControls: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backwardLabel = stringResource(R.string.seek_backward_10_seconds)
    val forwardLabel = stringResource(R.string.seek_forward_10_seconds)
    Row(
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.66f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight()
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onShowControls,
                    onDoubleClick = { onSeek(-SEEK_OFFSET_MS) },
                    onClickLabel = backwardLabel,
                    role = Role.Button,
                ).semantics { contentDescription = backwardLabel },
        )
        Spacer(Modifier.weight(0.55f).fillMaxHeight())
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight()
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onShowControls,
                    onDoubleClick = { onSeek(SEEK_OFFSET_MS) },
                    onClickLabel = forwardLabel,
                    role = Role.Button,
                ).semantics { contentDescription = forwardLabel },
        )
    }
}
