package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.SearchPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeAccountHistoryRepositoryTest {
    private val repository = YouTubeAccountHistoryRepository()
    private val configuration = YouTubeAccountHistoryRepository.Configuration(
        clientVersion = "2.20261119.01.00",
        apiKey = "AIza0123456789abcdefghijklmnopqrstuv",
        visitorData = "visitor-id",
    )

    @Test
    fun firstPageUsesHistoryBrowseIdWithoutAccountDataInTheBody() {
        val body = repository.historyBrowseBody(configuration, "uk", null)

        assertEquals("FEhistory", body.getString("browseId"))
        assertFalse(body.has("continuation"))
        assertFalse(body.toString().contains("access_token", ignoreCase = true))
        assertFalse(body.toString().contains("Authorization", ignoreCase = true))
        assertEquals("uk", body.getJSONObject("context").getJSONObject("client").getString("hl"))
        assertEquals("visitor-id", body.getJSONObject("context").getJSONObject("client").getString("visitorData"))
    }

    @Test
    fun continuationPageDoesNotRepeatTheInitialBrowseId() {
        val body = repository.historyBrowseBody(configuration, "en", "opaque-next-page-token")

        assertEquals("opaque-next-page-token", body.getString("continuation"))
        assertFalse(body.has("browseId"))
    }

    @Test
    fun historyBrowseBodyUsesOnlyYouTubeWebClientContext() {
        val body = repository.historyBrowseBody(configuration, "ru", null)
        val client = body.getJSONObject("context").getJSONObject("client")

        assertEquals("WEB", client.getString("clientName"))
        assertEquals(configuration.clientVersion, client.getString("clientVersion"))
        assertEquals("ru", client.getString("hl"))
        assertTrue(body.toString().contains("FEhistory"))
    }

    @Test
    fun sharedBrowseParserKeepsHistoryVideoAndContinuation() {
        val renderer = JSONObject()
            .put("videoId", "aqz-KE-bpKQ")
            .put("title", JSONObject().put("simpleText", "History fixture"))
            .put("ownerText", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Fixture channel"))))
            .put("publishedTimeText", JSONObject().put("simpleText", "2 days ago"))
            .put("lengthText", JSONObject().put("simpleText", "1:23"))
            .put("thumbnail", JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"))))
        val root = JSONObject()
            .put("contents", JSONObject().put("browseResultsRenderer", JSONObject().put("contents", JSONArray().put(JSONObject().put("videoRenderer", renderer)))))
            .put("continuationContents", JSONObject().put("sectionListContinuation", JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("continuationItemRenderer", JSONObject().put(
                    "continuationEndpoint",
                    JSONObject().put("continuationCommand", JSONObject().put("token", "next-page")),
                ))),
            )))

        val page: SearchPage = InnerTubeSearchRepository().parseSearchPage(root)

        assertEquals(1, page.items.size)
        assertEquals("aqz-KE-bpKQ", page.items.single().id)
        assertEquals("History fixture", page.items.single().title)
        assertEquals("Fixture channel", page.items.single().channel)
        assertEquals("1:23", page.items.single().duration)
        assertEquals("next-page", page.continuation)
    }
}
