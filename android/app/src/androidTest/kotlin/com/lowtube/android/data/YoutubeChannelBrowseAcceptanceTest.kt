package com.lowtube.android.data

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public metadata check for the channel URL that Search cards open; never starts playback. */
@RunWith(AndroidJUnit4::class)
class YoutubeChannelBrowseAcceptanceTest {
    @Test
    fun opensCanonicalChannelUrlAsAnInternalVideoFeed() {
        runBlocking {
            assumeTrue(
                "Live public-channel acceptance is opt-in; pass -e runChannelBrowseAcceptance true",
                InstrumentationRegistry.getArguments().getString("runChannelBrowseAcceptance") == "true",
            )

            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val repository = InnerTubeSearchRepository(context)
            val channel = repository.resolveChannel("@GoogleDevelopers", "en")
            assertTrue("Expected YouTube to resolve a canonical UC channel ID", channel.id.startsWith("UC"))
            assertEquals("https://www.youtube.com/channel/${channel.id}", channel.url)

            val page = repository.search(channel.url, language = "en")
            assertTrue("Internal channel browse returned no cards", page.items.isNotEmpty())
            assertTrue(
                "Internal channel browse returned no displayable feed items",
                page.items.any { it.kind in DISPLAYABLE_CHANNEL_ITEMS },
            )

            Log.i(
                TAG,
                "channelTitle=${channel.title} itemCount=${page.items.size} " +
                    "videoCount=${page.items.count { it.kind == "video" }} " +
                    "shortsCount=${page.items.count { it.kind == "shorts" }} hasContinuation=${!page.continuation.isNullOrBlank()}",
            )
        }
    }

    private companion object {
        const val TAG = "LowTubeChannelBrowse"
        val DISPLAYABLE_CHANNEL_ITEMS = setOf("video", "shorts", "live", "playlist")
    }
}
