package com.lowtube.android.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelBrowseRoutingTest {
    @Test
    fun channelTabsMapToCanonicalYouTubeBrowseUrls() {
        val base = "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"

        assertEquals("$base/videos", channelBrowseUrl(base))
        assertEquals("$base/shorts", channelBrowseUrl(base, "shorts"))
        assertEquals("$base/streams", channelBrowseUrl(base, "live"))
        assertEquals("$base/playlists", channelBrowseUrl(base, "playlist"))
        assertEquals("live", channelBrowseTab("https://m.youtube.com/channel/UCabcdefghijklmnopqrstuv/streams")?.kind)
    }

    @Test
    fun handlesAndLegacyChannelAliasesKeepTheirRoot() {
        assertEquals(
            "https://www.youtube.com/@Science_Channel/shorts",
            channelBrowseUrl("https://www.youtube.com/@Science_Channel/videos", "shorts"),
        )
        assertEquals(
            "https://www.youtube.com/c/ScienceDaily/playlists",
            channelBrowseUrl("https://www.youtube.com/c/ScienceDaily", "playlist"),
        )
        assertEquals(
            "https://www.youtube.com/user/ScienceDaily/streams",
            channelBrowseUrl("http://youtube.com/user/ScienceDaily/videos?view=0", "live"),
        )
    }

    @Test
    fun rejectsUntrustedAndMalformedChannelUrls() {
        assertNull(channelBrowseTab("https://youtube.com.evil.example/channel/UCabcdefghijklmnopqrstuv"))
        assertNull(channelBrowseTab("https://www.youtube.com/channel/short"))
        assertNull(channelBrowseUrl("https://www.youtube.com/playlist?list=PL123", "shorts"))
    }
}
