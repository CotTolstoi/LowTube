package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real public-channel metadata check; it makes no player request and never starts playback. */
@RunWith(AndroidJUnit4::class)
class ChannelBrowseHeaderAcceptanceTest {
    @Test
    fun publicChannelBrowseReturnsHeaderAndVideos() {
        assumeTrue(
            "Live channel acceptance is opt-in; pass -e runChannelHeaderAcceptance true",
            InstrumentationRegistry.getArguments().getString("runChannelHeaderAcceptance") == "true",
        )

        val repository = InnerTubeSearchRepository()
        val page = runBlocking { withTimeout(90_000) { repository.search(CHANNEL_URL, language = "en") } }
        val header = page.channelHeader
        assertTrue("Channel browse returned no header; items=${page.items.size}", header != null)
        assertTrue("Channel response had no title", header!!.title.isNotBlank())
        assertTrue("Channel ID was not resolved: ${header.channelId}", header.channelId.matches(Regex("UC[A-Za-z0-9_-]{22}")))
        assertTrue("Channel browse returned no videos", page.items.isNotEmpty())
    }

    private companion object {
        const val CHANNEL_URL = "https://www.youtube.com/@NASA/videos"
    }
}
