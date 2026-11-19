package com.lowtube.android.ui.library

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdaptiveLibraryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun libraryShowsDetailPaneOnlyWhenWindowIsWideEnough() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "adaptive-library-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val playlistId = repository.createPlaylist("Tablet split playlist")
        repository.addVideo(playlistId, VideoItem("tabletvideo01", "Tablet detail item", url = "https://www.youtube.com/watch?v=tabletvideo01"))
        try {
            compose.setContent {
                MaterialTheme {
                    LibraryScreen(
                        repository = repository,
                        accountEmail = "",
                        refreshRevision = 0,
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
            compose.waitForIdle()
            val wideWindow = InstrumentationRegistry.getArguments().getString("libraryLayout") == "twoPane"
            val detailPanes = compose.onAllNodesWithTag(LIBRARY_DETAIL_PANE_TEST_TAG).fetchSemanticsNodes().size
            assertEquals(if (wideWindow) 1 else 0, detailPanes)
            if (wideWindow) {
                compose.onNodeWithTag(LIBRARY_LIST_TEST_TAG).performScrollToNode(hasText("Tablet split playlist"))
                compose.onNodeWithText("Tablet split playlist").performClick()
                compose.onNodeWithText("Tablet detail item").assertExists()
            }
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
}
