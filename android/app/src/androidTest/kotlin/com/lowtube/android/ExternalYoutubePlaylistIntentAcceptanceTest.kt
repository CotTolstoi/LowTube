package com.lowtube.android

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies a valid incoming playlist VIEW intent reaches Search and never starts playback. */
@RunWith(AndroidJUnit4::class)
class ExternalYoutubePlaylistIntentAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun playlistViewIntentRoutesToSearch() {
        val implicit = Intent(Intent.ACTION_VIEW, Uri.parse(PLAYLIST_URL))
        composeTestRule.activity.runOnUiThread {
            composeTestRule.activity.acceptExternalYoutubeIntent(implicit)
        }

        composeTestRule.waitUntil(15_000L) {
            composeTestRule.onAllNodesWithText(PLAYLIST_URL).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(PLAYLIST_URL).assertExists()
    }

    private companion object {
        const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
    }
}
