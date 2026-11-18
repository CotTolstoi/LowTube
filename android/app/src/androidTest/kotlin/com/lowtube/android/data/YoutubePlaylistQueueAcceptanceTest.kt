package com.lowtube.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live metadata/SQLite check of a real playlist continuation; it never constructs Media3. */
@RunWith(AndroidJUnit4::class)
class YoutubePlaylistQueueAcceptanceTest {
    @Test
    fun publicPlaylistPagesExpandIntoQueueWithoutPlayback() = runBlocking {
        assumeTrue(
            "Live playlist continuation acceptance is opt-in; pass -e runPlaylistQueueAcceptance true",
            InstrumentationRegistry.getArguments().getString("runPlaylistQueueAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "youtube-playlist-continuation-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        try {
            val marker = VideoItem(id = PLAYLIST_ID, title = "Public playlist", kind = "playlist", url = PLAYLIST_URL)
            library.enqueue(marker)
            val catalogue = InnerTubeSearchRepository(context)
            val firstPage = catalogue.search(PLAYLIST_URL, language = "en")
            var page = firstPage
            var pagesExpanded = 0
            val expectedIds = mutableListOf<String>()
            while (true) {
                val videos = page.items.filter { it.kind == "video" }
                assertTrue("Playlist page $pagesExpanded returned no videos", videos.isNotEmpty())
                expectedIds += videos.map { it.id }
                val marker = library.queue().single { it.video.kind == "playlist" }
                library.expandQueuedPlaylist(marker.rowId, videos, page.continuation)
                pagesExpanded++
                if (page.continuation.isNullOrBlank()) break
                assertTrue("The live playlist returned too many pages for this bounded acceptance", pagesExpanded < MAX_PAGES)
                val persistedMarker = library.queue().single { it.video.kind == "playlist" }
                assertEquals(page.continuation, persistedMarker.continuation)
                page = catalogue.search(PLAYLIST_URL, continuation = persistedMarker.continuation, language = "en")
            }

            val expanded = library.queue()
            assertEquals(expectedIds, expanded.map { it.video.id })
            assertFalse("The final playlist marker remained in the queue", expanded.any { it.video.kind == "playlist" })
            assertTrue("The acceptance did not expand any playlist pages", pagesExpanded > 0)
        } finally {
            library.close()
            context.deleteDatabase(databaseName)
        }
    }

    private companion object {
        const val PLAYLIST_ID = "PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
        const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=$PLAYLIST_ID"
        const val MAX_PAGES = 4
    }
}
