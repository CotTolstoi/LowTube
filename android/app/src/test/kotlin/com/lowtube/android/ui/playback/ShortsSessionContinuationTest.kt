package com.lowtube.android.ui.playback

import com.lowtube.android.model.SearchPage
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortsSessionContinuationTest {
    @Test
    fun skipsPagesWithoutNewShortsAndDeduplicatesAgainstTheCurrentSession() = runBlocking {
        val existing = video("existing", "shorts")
        val pages = listOf(
            SearchPage(listOf(video("regular", "video"), existing), "next-2"),
            SearchPage(listOf(existing, video("next-short", "shorts")), "next-3"),
        )
        var requests = 0

        val result = loadShortsContinuation(listOf(existing), "next-1") { token ->
            assertEquals("next-${requests + 1}", token)
            pages[requests++]
        }

        assertEquals(listOf("next-short"), result.items.map { it.id })
        assertEquals("next-3", result.continuation)
        assertEquals(2, result.pagesRead)
        assertEquals(2, requests)
    }

    @Test
    fun stopsOnARepeatedContinuationToken() = runBlocking {
        var requests = 0
        val result = loadShortsContinuation(emptyList(), "same-token") {
            requests++
            SearchPage(emptyList(), "same-token")
        }

        assertEquals(1, requests)
        assertEquals(1, result.pagesRead)
        assertEquals("same-token", result.continuation)
    }

    @Test
    fun boundsEmptyPagesPerGesture() = runBlocking {
        var requests = 0
        val result = loadShortsContinuation(emptyList(), "next-1") {
            requests++
            SearchPage(emptyList(), "next-${requests + 1}")
        }

        assertEquals(3, requests)
        assertEquals(3, result.pagesRead)
        assertEquals("next-4", result.continuation)
    }

    @Test
    fun keepsTheSessionWithinItsItemLimit() = runBlocking {
        val existing = listOf(video("one", "shorts"), video("two", "shorts"))
        val result = loadShortsContinuation(existing, "next", maxItems = 3) {
            SearchPage(listOf(video("three", "shorts"), video("four", "shorts")), null)
        }

        assertEquals(listOf("three"), result.items.map { it.id })
        assertEquals(null, result.continuation)
    }

    private fun video(id: String, kind: String) = VideoItem(id, id, kind = kind, url = "https://youtu.be/$id")
}
