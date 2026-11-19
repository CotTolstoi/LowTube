package com.lowtube.android

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies an incoming playlist VIEW intent can place a playlist marker in the queue without playback. */
@RunWith(AndroidJUnit4::class)
class ExternalYoutubePlaylistIntentAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun playlistViewIntentCanBeQueuedWithoutPlayback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val library = LocalLibraryRepository(context)
        val initialRowIds = library.queue().mapTo(HashSet()) { it.rowId }
        var queueScreenOpened = false
        try {
            assertFalse("The acceptance playlist is already in the local queue", library.queue().any { it.video.id == PLAYLIST_ID })
            val implicit = Intent(Intent.ACTION_VIEW, Uri.parse(PLAYLIST_URL))
            composeTestRule.activity.runOnUiThread {
                composeTestRule.activity.acceptExternalYoutubeIntent(implicit)
            }

            composeTestRule.waitUntil(15_000L) {
                composeTestRule.onAllNodesWithText(PLAYLIST_URL).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(PLAYLIST_URL).assertExists()
            composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.add_playlist_to_queue_action)).performClick()
            composeTestRule.waitUntil(5_000L) {
                library.queue().any { it.video.id == PLAYLIST_ID && it.video.kind == "playlist" }
            }
            val marker = library.queue().single { it.video.id == PLAYLIST_ID && it.video.kind == "playlist" }
            composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.more_menu), substring = true).performClick()
            composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.watch_queue)).performClick()
            queueScreenOpened = true
            assertFalse(
                "The queue screen did not display the inserted playlist marker",
                composeTestRule.onAllNodesWithText(composeTestRule.activity.getString(R.string.playlist)).fetchSemanticsNodes().isEmpty(),
            )
            composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.load_playlist_page)).performClick()
            composeTestRule.waitUntil(90_000L) {
                val queue = library.queue()
                queue.any { it.rowId != marker.rowId && it.rowId !in initialRowIds && it.video.kind != "playlist" } ||
                    queue.none { it.rowId == marker.rowId }
            }
            val expandedQueue = library.queue()
            val expandedVideos = expandedQueue.filter { it.rowId !in initialRowIds && it.video.kind != "playlist" }
            val continuationMarker = expandedQueue.firstOrNull { it.rowId == marker.rowId && it.video.kind == "playlist" }
            assertTrue("The playlist page should add videos to the queue", expandedVideos.isNotEmpty())
            assertNotNull("The next playlist page marker should remain in the queue", continuationMarker)
            assertTrue("The playlist continuation should be persisted", continuationMarker?.continuation?.isNotBlank() == true)
        } finally {
            library.queue().filter { it.rowId !in initialRowIds }.forEach { library.removeQueueEntry(it.rowId) }
            library.close()
            if (queueScreenOpened) {
                composeTestRule.activity.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
                composeTestRule.waitForIdle()
            }
        }
    }

    private companion object {
        const val PLAYLIST_ID = "PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
        const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=$PLAYLIST_ID"
    }
}
