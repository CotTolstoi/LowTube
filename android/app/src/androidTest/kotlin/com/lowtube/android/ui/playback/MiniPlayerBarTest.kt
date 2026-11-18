package com.lowtube.android.ui.playback

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.model.VideoItem
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MiniPlayerBarTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentVideoAndOpenActionAreVisible() {
        var reopenRequested = false
        val video = VideoItem(
            id = "mini-player-fixture",
            title = "Mini player fixture",
            channel = "Fixture channel",
            url = "https://www.youtube.com/watch?v=mini-player-fixture",
        )

        composeTestRule.setContent {
            LowTubeTheme {
                MiniPlayerBar(video = video, onOpenPlayer = { reopenRequested = true })
            }
        }

        composeTestRule.onNodeWithText(video.title).assertExists().performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(com.lowtube.android.R.string.mini_player_open)).assertExists()
        assertTrue("Mini-player should return to the full player", reopenRequested)
    }
}
