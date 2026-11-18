package com.lowtube.android.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public playlist browse check. It only resolves metadata and never opens Media3 playback. */
@RunWith(AndroidJUnit4::class)
class YoutubePlaylistBrowseAcceptanceTest {
    @Test
    fun opensPublicPlaylistAsInternalVideoItems() = runBlocking {
        assumeTrue(
            "Live public-playlist acceptance is opt-in; pass -e runPlaylistBrowseAcceptance true",
            InstrumentationRegistry.getArguments().getString("runPlaylistBrowseAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val page = InnerTubeSearchRepository(context).search(PLAYLIST_URL, language = "en")
        val videos = page.items.filter { it.kind == "video" }

        assertTrue("Public playlist browse returned no video items", videos.isNotEmpty())
        assertTrue("Playlist browse returned incomplete video cards", videos.all { it.id.isNotBlank() && it.title.isNotBlank() })
    }

    private companion object {
        const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"
    }
}
