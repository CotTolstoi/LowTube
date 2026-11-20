package com.lowtube.android.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val PLAYBACK_VIDEO_PANE_TEST_TAG = "playback_video_pane"
internal const val PLAYBACK_DETAILS_PANE_TEST_TAG = "playback_details_pane"
internal const val PLAYBACK_COMPACT_LAYOUT_TEST_TAG = "playback_compact_layout"

@Composable
internal fun AdaptivePlaybackLayout(
    hasVideoPane: Boolean,
    modifier: Modifier = Modifier,
    videoPane: @Composable ColumnScope.() -> Unit,
    detailsPane: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        if (hasVideoPane && maxWidth >= 840.dp) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1280.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.45f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .testTag(PLAYBACK_VIDEO_PANE_TEST_TAG),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = videoPane,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .testTag(PLAYBACK_DETAILS_PANE_TEST_TAG),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = detailsPane,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag(PLAYBACK_COMPACT_LAYOUT_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                videoPane()
                detailsPane()
            }
        }
    }
}
