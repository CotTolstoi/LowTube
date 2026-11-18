package com.lowtube.android.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.model.VideoItem
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoCardChannelNavigationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun channelTitleOpensChannelWithoutTriggeringVideoPlayback() {
        var channelNavigationRequested = false
        var videoPlaybackRequested = false
        val video = VideoItem(
            id = "fixture-video",
            title = "Fixture video",
            channel = "Fixture channel",
            channelId = "UC0123456789abcdefghijklmn",
            url = "https://www.youtube.com/watch?v=fixture-video",
        )

        composeTestRule.setContent {
            LowTubeTheme {
                VideoCard(
                    item = video,
                    isFollowing = false,
                    onQueue = {},
                    onDownload = {},
                    onSave = {},
                    onToggleFollow = { true },
                    onChannelClick = { channelNavigationRequested = true },
                    onShare = {},
                    onClick = { videoPlaybackRequested = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Fixture channel").performClick()

        assertTrue("Channel title should request channel navigation", channelNavigationRequested)
        assertFalse("Channel title should not start the video's playback", videoPlaybackRequested)
    }
}
