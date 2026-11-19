package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelHeaderParsingTest {
    @Test
    fun channelBrowseResponseProvidesHeaderAndKeepsVideoCards() {
        val root = JSONObject(
            """
            {
              "header": {"c4TabbedHeaderRenderer": {
                "channelId": "UCabcdefghijklmnopqrstuv",
                "title": {"simpleText": "Science Channel"},
                "avatar": {"thumbnails": [
                  {"url": "https://yt3.ggpht.com/avatar/s88-c-k-c0x00ffffff-no-rj"}
                ]},
                "banner": {"thumbnails": [
                  {"url": "https://yt3.googleusercontent.com/banner=s1024"}
                ]},
                "subscriberCountText": {"simpleText": "1.2M subscribers"},
                "description": {"simpleText": "Public science videos."}
              }},
              "contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{
                "tabRenderer": {"content": {"sectionListRenderer": {"contents": [{
                  "itemSectionRenderer": {"contents": [{"videoRenderer": {
                    "videoId": "abcdefghijk",
                    "title": {"simpleText": "A public video"}
                  }}]}
                }]}}}
              }]}}
            }
            """.trimIndent(),
        )

        val page = InnerTubeSearchRepository().parseSearchPage(root)
        val header = page.channelHeader

        assertNotNull(header)
        assertEquals("UCabcdefghijklmnopqrstuv", header?.channelId)
        assertEquals("Science Channel", header?.title)
        assertEquals("1.2M subscribers", header?.subscriberCountText)
        assertEquals("Public science videos.", header?.description)
        assertEquals("https://yt3.ggpht.com/avatar/s88-c-k-c0x00ffffff-no-rj", header?.avatarUrl)
        assertEquals("https://yt3.googleusercontent.com/banner=s1024", header?.bannerUrl)
        assertEquals(1, page.items.size)
    }
}
