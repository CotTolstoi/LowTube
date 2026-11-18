package com.lowtube.android.ui.library

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LocalPlaylistPlaybackActionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun playAllActionPassesPlaylistOrderWithoutStartingPlayerItself() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources: Resources = context.resources
        val databaseName = "playlist-play-all-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val playlistId = repository.createPlaylist("Test playlist")
        repository.addVideo(playlistId, video("video000001", "First playlist item"))
        repository.addVideo(playlistId, video("video000002", "Second playlist item"))
        val requestedVideos = AtomicReference<List<VideoItem>>(emptyList())
        try {
            composeTestRule.setContent {
                MaterialTheme {
                    LibraryScreen(
                        repository = repository,
                        accountEmail = "",
                        refreshRevision = 0,
                        onBack = {},
                        onPlay = {},
                        onPlayPlaylist = { requestedVideos.set(it) },
                        onPickAudio = {},
                        onPlayLocalAudio = {},
                        onRemoveLocalAudio = {},
                        onAddToQueue = {},
                        onBrowseChannel = {},
                        onAddChannel = {},
                    )
                }
            }

            composeTestRule.onNodeWithText("Test playlist").performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.play_all)).performClick()
            composeTestRule.waitForIdle()

            assertEquals(listOf("video000001", "video000002"), requestedVideos.get().map { it.id })
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun video(id: String, title: String) = VideoItem(
        id = id,
        title = title,
        url = "https://www.youtube.com/watch?v=$id",
    )
}
