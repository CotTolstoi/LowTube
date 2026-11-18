package com.lowtube.android.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises persisted playlist-marker expansion without creating a Media3 player. */
class LocalPlaylistQueueAcceptanceTest {
    @Test
    fun playlistPagesExpandInPlaceAndRetainContinuationUntilFinalPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "playlist-queue-${System.currentTimeMillis()}.db"
        var repository = LocalLibraryRepository(context, databaseName)
        try {
            repository.enqueue(video("playlist000", "Playlist", kind = "playlist"))
            repository.enqueue(video("video000001", "Existing next item"))
            val marker = repository.queue().first()

            repository.expandQueuedPlaylist(
                marker.rowId,
                listOf(video("video000002", "Page one A"), video("video000003", "Page one B")),
                "continuation-page-two",
            )

            val afterFirstPage = repository.queue()
            assertEquals(
                listOf("video000002", "video000003", "playlist000", "video000001"),
                afterFirstPage.map { it.video.id },
            )
            assertEquals("continuation-page-two", afterFirstPage[2].continuation)

            repository.close()
            repository = LocalLibraryRepository(context, databaseName)
            val restoredAfterReopen = repository.queue()
            assertEquals(afterFirstPage.map { it.video.id }, restoredAfterReopen.map { it.video.id })
            assertEquals("continuation-page-two", restoredAfterReopen[2].continuation)

            repository.expandQueuedPlaylist(
                restoredAfterReopen[2].rowId,
                listOf(video("video000004", "Page two")),
                null,
            )

            val afterFinalPage = repository.queue()
            assertEquals(
                listOf("video000002", "video000003", "video000004", "video000001"),
                afterFinalPage.map { it.video.id },
            )
            assertEquals(0, afterFinalPage.count { it.video.kind == "playlist" })
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
