package com.lowtube.android.ui.main

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.model.VideoItem
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoCardShareTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shareActionPassesTheItemToShareWithoutStartingPlayback() {
        var shareRequested = false
        var playbackRequested = false
        val item = fixture()

        composeTestRule.setContent {
            LowTubeTheme {
                VideoCard(
                    item = item,
                    isFollowing = false,
                    onQueue = {},
                    onDownload = {},
                    onSave = {},
                    onToggleFollow = { true },
                    onChannelClick = {},
                    onShare = { shareRequested = true },
                    onClick = { playbackRequested = true },
                )
            }
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.more_actions)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.share_link)).performClick()

        assertTrue("Share menu item should invoke the item's share action", shareRequested)
        assertFalse("Sharing must not start playback", playbackRequested)
    }

    @Test
    fun shareIntentContainsTitleAndTrustedYoutubeLink() {
        val item = fixture()
        val intent = createShareIntent(item)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Fixture video\n${item.url}", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun shareIntentRejectsNonYoutubeUrls() {
        val unsafe = fixture().copy(url = "https://example.org/video")

        assertFalse(runCatching { createShareIntent(unsafe) }.isSuccess)
    }

    private fun fixture() = VideoItem(
        id = "fixture-video",
        title = "Fixture video",
        channel = "Fixture channel",
        channelId = "UC0123456789abcdefghijklmn",
        url = "https://www.youtube.com/watch?v=fixture-video",
    )
}
