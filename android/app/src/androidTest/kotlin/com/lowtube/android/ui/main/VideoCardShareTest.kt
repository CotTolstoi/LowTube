package com.lowtube.android.ui.main

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.more_actions)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.share_link)).performClick()

        assertTrue("Share menu item should invoke the item's share action", shareRequested)
        assertFalse("Sharing must not start playback", playbackRequested)
    }

    @Test
    fun queueActionRemainsAvailableFromOverflowMenu() {
        var queueRequested = false
        var playbackRequested = false
        composeTestRule.setContent {
            LowTubeTheme {
                VideoCard(
                    item = fixture(),
                    isFollowing = false,
                    onQueue = { queueRequested = true },
                    onDownload = {},
                    onSave = {},
                    onToggleFollow = { true },
                    onChannelClick = {},
                    onShare = {},
                    onClick = { playbackRequested = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.more_actions)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.add_to_queue)).performClick()

        assertTrue("Queue action should remain available", queueRequested)
        assertFalse("Queue action must not start playback", playbackRequested)
    }

    @Test
    fun playlistQueueActionDoesNotNavigateWhenCardLivesInLazyColumn() {
        var queueRequested = false
        var playlistOpened = false
        composeTestRule.setContent {
            LowTubeTheme {
                LazyColumn {
                    item {
                        VideoCard(
                            item = fixture().copy(
                                id = "PL0123456789abcdefghijABCDEFGHIJKLM",
                                title = "Playlist fixture",
                                kind = "playlist",
                                url = "https://www.youtube.com/playlist?list=PL0123456789abcdefghijABCDEFGHIJKLM",
                            ),
                            isFollowing = false,
                            onQueue = { queueRequested = true },
                            onDownload = {},
                            onSave = {},
                            onToggleFollow = { true },
                            onChannelClick = {},
                            onShare = {},
                            onClick = { playlistOpened = true },
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.more_actions)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.add_to_queue)).performClick()

        assertTrue("Queue action should remain available on a playlist card", queueRequested)
        assertFalse("Queue action must not open the playlist", playlistOpened)
    }

    @Test
    fun playlistHasDirectQueueActionWithoutOpeningThePlaylist() {
        var queueRequested = false
        var playlistOpened = false
        composeTestRule.setContent {
            LowTubeTheme {
                VideoCard(
                    item = fixture().copy(
                        id = "PL0123456789abcdefghijABCDEFGHIJKLM",
                        title = "Playlist fixture",
                        kind = "playlist",
                        url = "https://www.youtube.com/playlist?list=PL0123456789abcdefghijABCDEFGHIJKLM",
                    ),
                    isFollowing = false,
                    onQueue = { queueRequested = true },
                    onDownload = {},
                    onSave = {},
                    onToggleFollow = { true },
                    onChannelClick = {},
                    onShare = {},
                    onClick = { playlistOpened = true },
                )
            }
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.add_playlist_to_queue_action)).performClick()

        assertTrue("The direct playlist action should enqueue the playlist marker", queueRequested)
        assertFalse("Enqueueing a playlist must not navigate into it", playlistOpened)
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
