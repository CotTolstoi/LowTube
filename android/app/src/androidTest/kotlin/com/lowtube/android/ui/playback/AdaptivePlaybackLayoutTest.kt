package com.lowtube.android.ui.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptivePlaybackLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wideVideoPlaybackPlacesDetailsBesidePlayer() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(1000.dp, 700.dp)) {
                    AdaptivePlaybackLayout(
                        hasVideoPane = true,
                        modifier = Modifier.fillMaxSize(),
                        videoPane = { Text("Player") },
                        detailsPane = { Text("Details") },
                    )
                }
            }
        }

        val video = compose.onNodeWithTag(PLAYBACK_VIDEO_PANE_TEST_TAG).assertExists().fetchSemanticsNode()
        val details = compose.onNodeWithTag(PLAYBACK_DETAILS_PANE_TEST_TAG).assertExists().fetchSemanticsNode()
        assertTrue("The details pane must be placed to the right of the player", details.boundsInRoot.left > video.boundsInRoot.left)
        assertTrue("The two panes must not overlap", details.boundsInRoot.left >= video.boundsInRoot.right)
    }

    @Test
    fun compactVideoPlaybackKeepsOneColumn() {
        setLayout(widthDp = 720, hasVideoPane = true)
        compose.onNodeWithTag(PLAYBACK_COMPACT_LAYOUT_TEST_TAG).assertExists()
        compose.onNodeWithTag(PLAYBACK_DETAILS_PANE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun audioOnlyPlaybackKeepsOneColumnOnWideWindow() {
        setLayout(widthDp = 1000, hasVideoPane = false)
        compose.onNodeWithTag(PLAYBACK_COMPACT_LAYOUT_TEST_TAG).assertExists()
        compose.onNodeWithTag(PLAYBACK_VIDEO_PANE_TEST_TAG).assertDoesNotExist()
    }

    private fun setLayout(widthDp: Int, hasVideoPane: Boolean) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(widthDp.dp, 700.dp)) {
                    AdaptivePlaybackLayout(
                        hasVideoPane = hasVideoPane,
                        modifier = Modifier.fillMaxSize(),
                        videoPane = { Text("Audio") },
                        detailsPane = { Text("Details") },
                    )
                }
            }
        }
    }
}
