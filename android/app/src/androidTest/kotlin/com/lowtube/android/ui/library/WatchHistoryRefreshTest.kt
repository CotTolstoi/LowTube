package com.lowtube.android.ui.library

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WatchHistoryRefreshTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun refreshedRevisionShowsProgressSavedBeforeReturningFromPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources: Resources = context.resources
        val databaseName = "watch-history-refresh-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val refreshRevision = mutableIntStateOf(0)
        val video = VideoItem(
            id = "history0001",
            title = "History refresh fixture",
            channel = "LowTube test channel",
            duration = "0:30",
            url = "https://www.youtube.com/watch?v=history0001",
        )

        try {
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

            val libraryList = composeTestRule.onNodeWithTag(LIBRARY_LIST_TEST_TAG)
            libraryList.performScrollToNode(hasText(resources.getString(R.string.history_empty)))
            composeTestRule.onNodeWithText(resources.getString(R.string.history_empty)).assertExists()

            repository.recordWatchProgress(video, positionMs = 5_000, durationMs = 30_000)
            composeTestRule.runOnIdle { refreshRevision.intValue++ }
            composeTestRule.waitForIdle()

            libraryList.performScrollToNode(hasText(video.title))
            composeTestRule.onNodeWithText(video.title).assertExists()
            composeTestRule.onNodeWithText("16% · ${video.duration}").assertExists()
            assertEquals(1, repository.watchHistory().size)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
}
