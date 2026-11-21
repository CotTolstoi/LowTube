package com.lowtube.android.ui.library

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class WatchHistoryActionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun removesOneHistoryEntryThenClearsTheRemainingLocalHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "watch-history-actions-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val refreshRevision = mutableIntStateOf(0)
        val first = video("history0001", "First history fixture")
        val second = video("history0002", "Second history fixture")
        repository.recordWatchProgress(first, positionMs = 5_000, durationMs = 30_000)
        repository.recordWatchProgress(second, positionMs = 12_000, durationMs = 30_000)

        try {
            assertEquals(first, repository.previousWatchedVideo(second.id))
            assertEquals(second, repository.previousWatchedVideo(first.id))
            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(
                        repository = repository,
                        accountEmail = "",
                        refreshRevision = refreshRevision.intValue,
                        onBack = {},
                        onPlay = {},
                        onPlayPlaylist = {},
                        onPickAudio = {},
                        onPlayLocalAudio = {},
                        onRemoveLocalAudio = {},
                        onAddToQueue = {},
                        onBrowseChannel = {},
                        onAddChannel = {},
                    )
                }
            }

            val list = composeTestRule.onNodeWithTag(LIBRARY_LIST_TEST_TAG)
            list.performScrollToNode(hasText(first.title))
            composeTestRule.onNodeWithText(first.title).assertExists()
            composeTestRule.onNodeWithText(second.title).assertExists()

            composeTestRule.onNodeWithTag("watch-history-remove-${first.id}").performClick()
            composeTestRule.waitForIdle()
            assertEquals(listOf(second.id), repository.watchHistory().map { it.video.id })
            assertNull(repository.previousWatchedVideo(second.id))
            composeTestRule.onNodeWithText(first.title).assertDoesNotExist()
            composeTestRule.onNodeWithText(second.title).assertExists()

            list.performScrollToNode(hasTestTag(WATCH_HISTORY_CLEAR_ACTION_TEST_TAG))
            composeTestRule.onNodeWithTag(WATCH_HISTORY_CLEAR_ACTION_TEST_TAG).performClick()
            composeTestRule.onNodeWithText(context.getString(R.string.clear_history_confirm)).assertExists()
            composeTestRule.onNodeWithTag(WATCH_HISTORY_CLEAR_CONFIRM_TEST_TAG).performClick()
            composeTestRule.waitForIdle()

            assertEquals(0, repository.watchHistory().size)
            list.performScrollToNode(hasText(context.getString(R.string.history_empty)))
            composeTestRule.onNodeWithText(context.getString(R.string.history_empty)).assertExists()
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun video(id: String, title: String) = VideoItem(
        id = id,
        title = title,
        channel = "LowTube test channel",
        duration = "0:30",
        url = "https://www.youtube.com/watch?v=$id",
    )
}
