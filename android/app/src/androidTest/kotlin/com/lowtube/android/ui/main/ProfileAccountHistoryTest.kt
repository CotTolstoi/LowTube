package com.lowtube.android.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileAccountHistoryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profileLoadsAndPlaysReadOnlyAccountHistoryRows() {
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "History fixture",
            channel = "Fixture channel",
            thumbnailUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        var requestedReload: Boolean? = null
        var playedVideo: VideoItem? = null
        composeRule.setContent {
            MaterialTheme {
                ProfileTab(
                    accountName = "Account",
                    accountEmail = "account@example.test",
                    accountMessage = "",
                    signInBusy = false,
                    youtubeHistoryItems = listOf(video),
                    youtubeHistoryBusy = false,
                    youtubeHistoryMessage = "Loaded 1 videos from YouTube history.",
                    youtubeHistoryHasMore = true,
                    onLoadYouTubeHistory = { requestedReload = it },
                    onPlayHistoryVideo = { playedVideo = it },
                    onSignIn = {},
                    onSignOut = {},
                    modifier = Modifier,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.youtube_history_title)).assertExists()
        composeRule.onNodeWithText("History fixture").assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.youtube_history_refresh)).performClick()
        assertEquals(true, requestedReload)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.play)).performClick()
        assertTrue(playedVideo === video)
    }
}
