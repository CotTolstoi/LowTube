package com.lowtube.android.ui.library

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun createsRenamesAndDeletesLocalPlaylistThroughLibraryUi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources: Resources = context.resources
        val databaseName = "playlist-management-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        try {
            composeTestRule.setContent {
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

            val originalName = "Local acceptance ${System.nanoTime()}"
            val renamedName = "$originalName renamed"
            composeTestRule.onNodeWithText(resources.getString(R.string.new_playlist)).performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.playlist_name)).performTextInput(originalName)
            composeTestRule.onNodeWithText(resources.getString(R.string.save)).performClick()
            composeTestRule.waitForIdle()
            val playlist = repository.playlists().single()
            assertEquals(originalName, playlist.name)

            composeTestRule.onAllNodesWithText(originalName).get(0).performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.rename)).performClick()
            val nameField = composeTestRule.onNodeWithText(resources.getString(R.string.playlist_name))
            nameField.performTextClearance()
            nameField.performTextInput(renamedName)
            composeTestRule.onNodeWithText(resources.getString(R.string.save)).performClick()
            composeTestRule.waitForIdle()
            assertEquals(renamedName, repository.playlists().single().name)

            composeTestRule.onAllNodesWithText(renamedName).get(0).performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.delete)).performClick()
            composeTestRule.onAllNodesWithText(resources.getString(R.string.delete)).get(1).performClick()
            composeTestRule.waitForIdle()
            assertTrue(repository.playlists().isEmpty())
            composeTestRule.onNodeWithText(resources.getString(R.string.library_empty)).assertExists()
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun addVideoUrlRejectsPlaylistLinksAndStoresCanonicalVideoLink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources: Resources = context.resources
        val databaseName = "playlist-add-url-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val playlistId = repository.createPlaylist("URL playlist")
        try {
            composeTestRule.setContent {
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

            composeTestRule.onNodeWithText("URL playlist").performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.add_video_url)).performClick()
            val urlField = composeTestRule.onNodeWithText(resources.getString(R.string.video_url_label))
            urlField.performTextInput("https://www.youtube.com/playlist?list=PL1234567890")
            composeTestRule.onNodeWithText(resources.getString(R.string.add_video)).performClick()
            composeTestRule.onNodeWithText(resources.getString(R.string.invalid_video_url)).assertExists()

            urlField.performTextClearance()
            urlField.performTextInput("https://youtu.be/video000001?t=12")
            composeTestRule.onNodeWithText(resources.getString(R.string.add_video)).performClick()
            composeTestRule.waitForIdle()

            val saved = repository.videos(playlistId).single()
            assertEquals("video000001", saved.id)
            assertEquals("https://www.youtube.com/watch?v=video000001", saved.url)
            assertEquals(saved.url, saved.title)
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
