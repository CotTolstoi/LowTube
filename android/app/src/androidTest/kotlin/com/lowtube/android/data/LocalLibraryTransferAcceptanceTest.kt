package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.WatchHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryTransferAcceptanceTest {
    @Test
    fun backupRoundTripsLocalChannelsPlaylistsAndProgressWithoutSecretsOrFileUris() {
        val video = video("abcdefghijk", "A saved video")
        val snapshot = LocalLibraryTransferRepository.Snapshot(
            channels = listOf(SavedChannel("UC1234567890123456789012", "A channel", "https://www.youtube.com/channel/UC1234567890123456789012", category = "Music")),
            playlists = listOf(LocalLibraryTransferRepository.PlaylistSnapshot("playlist_1", "Favorites", listOf(video))),
            history = listOf(WatchHistoryEntry(video, 12_000L, 60_000L, false, 1_700_000_000_000L)),
        )

        val encoded = LocalLibraryTransferRepository.encode(snapshot)
        val decoded = LocalLibraryTransferRepository.decode(encoded)

        assertEquals(snapshot.channels, decoded.channels)
        assertEquals(snapshot.playlists.single().id, decoded.playlists.single().id)
        assertEquals(snapshot.playlists.single().videos.single().id, decoded.playlists.single().videos.single().id)
        assertEquals(snapshot.history.single().positionMs, decoded.history.single().positionMs)
        assertEquals(snapshot.history.single().updatedAt, decoded.history.single().updatedAt)
        assertFalse(encoded.contains("accessToken", ignoreCase = true))
        assertFalse(encoded.contains("AccountEmail", ignoreCase = true))
        assertFalse(encoded.contains("content://"))
    }

    @Test
    fun importReplacesLocalLibraryAndQueueButKeepsAccountCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "library-transfer-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val repository = LocalLibraryRepository(context, databaseName)
        val targetVideo = video("abcdefghijk", "Saved in backup")
        val oldVideo = video("lmnopqrstuv", "Old local item")
        val queuedVideo = video("wxyzABCDEFG", "Queue item")
        val remoteChannelId = "UCabcdefghijklmnopqrstuv"
        val localChannelId = "UC1234567890123456789012"
        try {
            repository.follow(localChannelId, "Saved channel", "https://www.youtube.com/channel/$localChannelId")
            val playlistId = repository.createPlaylist("Favorites")
            repository.addVideo(playlistId, targetVideo)
            repository.recordWatchProgress(targetVideo, 12_000L, 60_000L)
            repository.enqueue(queuedVideo)
            repository.setYouTubeAccountSubscription("test@example.invalid", remoteChannelId, "Remote account channel", true)
            val backup = LocalLibraryTransferRepository.decode(LocalLibraryTransferRepository.encode(repository.exportLocalLibrary()))

            repository.clearQueue()
            repository.enqueue(oldVideo)
            repository.unfollow(localChannelId)
            repository.deletePlaylist(playlistId)
            repository.follow("UCabcdefghijklmnopqrstuv", "Temporary channel", "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv")
            val replacementPlaylist = repository.createPlaylist("Temporary")
            repository.addVideo(replacementPlaylist, oldVideo)
            repository.recordWatchProgress(oldVideo, 8_000L, 30_000L)

            repository.replaceLocalLibrary(backup)

            assertEquals(listOf(localChannelId), repository.localChannels().map { it.id })
            assertEquals("Favorites", repository.playlists().single().name)
            assertEquals(listOf(targetVideo.id), repository.videos(repository.playlists().single().id).map { it.id })
            assertEquals(12_000L, repository.resumePositionMs(targetVideo.id))
            assertEquals(listOf(queuedVideo.id), repository.queue().map { it.video.id })
            assertTrue(repository.channels("test@example.invalid").any { it.id == remoteChannelId && it.fromYouTubeAccount })
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun rejectsUnsupportedOrUnboundedBackupData() {
        assertTrue(runCatching { LocalLibraryTransferRepository.decode("{}") }.isFailure)
        val tooLarge = " ".repeat(16 * 1024 * 1024 + 1)
        assertTrue(runCatching { LocalLibraryTransferRepository.decode(tooLarge) }.isFailure)
    }

    private fun video(id: String, title: String) = VideoItem(
        id = id,
        title = title,
        channel = "Fixture channel",
        channelId = "UC1234567890123456789012",
        url = "https://www.youtube.com/watch?v=$id",
    )
}
