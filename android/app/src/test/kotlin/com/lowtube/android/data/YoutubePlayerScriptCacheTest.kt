package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubePlayerScriptCacheTest {
    @Test
    fun normalizesOnlyYouTubePlayerBaseScripts() {
        assertEquals(
            "https://www.youtube.com/s/player/01234567/player_ias.vflset/en_US/base.js",
            YoutubePlayerScriptCache.normalizeUrl("/s/player/01234567/player_ias.vflset/en_US/base.js"),
        )
        assertEquals(
            "https://youtube.com/s/player/01234567/base.js",
            YoutubePlayerScriptCache.normalizeUrl("https://youtube.com/s/player/01234567/base.js"),
        )
    }

    @Test
    fun rejectsUntrustedOrUnexpectedPlayerScriptUrls() {
        listOf(
            "http://www.youtube.com/s/player/01234567/base.js",
            "https://attacker.example/s/player/01234567/base.js",
            "https://www.youtube.com.evil.example/s/player/01234567/base.js",
            "https://www.youtube.com:444/s/player/01234567/base.js",
            "https://user@www.youtube.com/s/player/01234567/base.js",
            "https://www.youtube.com/api/player/base.js",
            "https://www.youtube.com/s/player/01234567/base.js#fragment",
        ).forEach { assertNull(it, YoutubePlayerScriptCache.normalizeUrl(it)) }
    }

    @Test
    fun extractsOnlyPlausiblePlayerSignatureTimestamps() {
        assertEquals(20445, YoutubePlayerScriptCache.extractSignatureTimestamp("var cfg={signatureTimestamp:20445};"))
        assertEquals(20445, YoutubePlayerScriptCache.extractSignatureTimestamp("x.sts = 20445;"))
        assertNull(YoutubePlayerScriptCache.extractSignatureTimestamp("var signatureTimestamp=12;"))
        assertNull(YoutubePlayerScriptCache.extractSignatureTimestamp("var otherValue=20445;"))
    }

    @Test
    fun cachesTheCurrentPlayerVersionAndReplacesItWithOneOtherVersion() {
        val requests = mutableListOf<String>()
        val cache = YoutubePlayerScriptCache { url ->
            requests += url
            "var signatureTimestamp=${if (url.contains("player-a")) 20445 else 20446};"
        }
        val playerA = "https://www.youtube.com/s/player/player-a/base.js"
        val playerB = "https://www.youtube.com/s/player/player-b/base.js"

        assertEquals(20445, cache.load(playerA).signatureTimestamp)
        assertEquals(20445, cache.load(playerA).signatureTimestamp)
        assertEquals(20446, cache.load(playerB).signatureTimestamp)
        assertEquals(20445, cache.load(playerA).signatureTimestamp)
        assertEquals(listOf(playerA, playerB, playerA), requests)
    }

    @Test
    fun invalidUrlsAreRejectedBeforeTheSourceFetcherRuns() {
        var fetchCount = 0
        val cache = YoutubePlayerScriptCache {
            fetchCount++
            "var signatureTimestamp=20445;"
        }

        assertThrows(java.io.IOException::class.java) {
            cache.load("https://attacker.example/s/player/player-a/base.js")
        }
        assertEquals(0, fetchCount)
    }

    @Test
    fun oversizedScriptsAreNotKeptInTheCache() {
        var fetchCount = 0
        val cache = YoutubePlayerScriptCache {
            fetchCount++
            if (fetchCount == 1) "x".repeat(YoutubePlayerScriptCache.MAX_SCRIPT_BYTES + 1)
            else "var signatureTimestamp=20445;"
        }
        val player = "https://www.youtube.com/s/player/player-a/base.js"

        assertThrows(java.io.IOException::class.java) { cache.load(player) }
        assertEquals(20445, cache.load(player).signatureTimestamp)
        assertEquals(2, fetchCount)
    }
}
