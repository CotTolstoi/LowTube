package com.lowtube.android.ui.queue

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.res.Resources
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class QueuePlaylistExpansionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadPlaylistPageReplacesMarkerInVisibleQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources: Resources = context.resources
        val databaseName = "queue-ui-playlist-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        repository.enqueue(video("playlist000", "Test playlist", kind = "playlist"))
        repository.enqueue(video("video000001", "Already queued"))
        try {
            composeTestRule.setContent {
                var revision by mutableIntStateOf(0)
                MaterialTheme {
                    QueueScreen(
                        repository = repository,
                        refreshRevision = revision,
                        onBack = {},
                        onPlay = {},
                        onExpandPlaylist = { entry ->
                            repository.expandQueuedPlaylist(entry.rowId, listOf(video("video000002", "Loaded playlist video")), null)
                            revision++
                        },
                        expandingPlaylistRowId = null,
                        autoNext = true,
                        onAutoNextChanged = {},
                    )
                }
            }

            composeTestRule.onNodeWithText("Test playlist").assertExists()
            composeTestRule.onNodeWithText("Already queued").assertExists()
            composeTestRule.onNodeWithText(resources.getString(R.string.load_playlist_page)).performClick()
            composeTestRule.waitForIdle()

            assertEquals(0, composeTestRule.onAllNodesWithText("Test playlist").fetchSemanticsNodes().size)
            composeTestRule.onNodeWithText("Loaded playlist video").assertExists()
            composeTestRule.onNodeWithText("Already queued").assertExists()
            assertEquals(listOf("video000002", "video000001"), repository.queue().map { it.video.id })
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun video(id: String, title: String, kind: String = "video") = VideoItem(
        id = id,
        title = title,
        kind = kind,
        url = "https://www.youtube.com/watch?v=$id",
    )
}
