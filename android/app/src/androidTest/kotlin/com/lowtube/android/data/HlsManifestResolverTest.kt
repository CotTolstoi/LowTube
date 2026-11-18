package com.lowtube.android.data

import com.lowtube.android.model.VideoItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsManifestResolverTest {
    private val repository = InnerTubeSearchRepository()
    private val video = VideoItem(
        id = "hls-fixture",
        title = "HLS fixture",
        duration = "9:56",
        url = "https://www.youtube.com/watch?v=hls-fixture",
    )

    @Test
    fun trustedHlsManifestIsAcceptedAndTypedForMedia3() {
        val url = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/123"
        val streaming = JSONObject().put("hlsManifestUrl", url)

        assertTrue(repository.hasDirectOrDash(streaming))
        val stream = repository.hlsManifestFallback(video, streaming)

        assertNotNull(stream)
        assertEquals(url, stream!!.uri)
        assertEquals("application/x-mpegURL", stream.mimeType)
        assertTrue(stream.isHlsManifest)
        assertEquals(596_000L, stream.durationMs)
    }

    @Test
    fun untrustedHlsManifestIsRejected() {
        val streaming = JSONObject().put("hlsManifestUrl", "https://example.invalid/manifest.m3u8")

        assertFalse(repository.hasDirectOrDash(streaming))
        assertNull(repository.hlsManifestFallback(video, streaming))
    }
}
