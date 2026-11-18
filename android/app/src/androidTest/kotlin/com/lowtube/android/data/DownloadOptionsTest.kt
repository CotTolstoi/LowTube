package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOptionsTest {
    private val repository = InnerTubeSearchRepository()
    private val video = VideoItem(id = "abcdefghijk", title = "Test video", url = "https://www.youtube.com/watch?v=abcdefghijk")

    @Test
    fun videoOptionsHonorSeparateHeightCodecAndFrameRateLimits() {
        val streaming = JSONObject().put(
            "formats",
            JSONArray()
                .put(videoFormat(22, 720, 30, 2_000_000, "avc1.64001F", "mp4a.40.2"))
                .put(videoFormat(18, 360, 30, 800_000, "avc1.42001E", "mp4a.40.2"))
                .put(videoFormat(23, 720, 60, 3_000_000, "avc1.640020", "mp4a.40.2"))
                .put(videoFormat(37, 1080, 30, 5_000_000, "avc1.640028", "mp4a.40.2"))
                .put(videoFormat(45, 720, 30, 2_500_000, "vp9", "opus", container = "webm"))
                .put(videoFormat(99, 720, 30, 2_500_000, "avc1.64001F", "mp4a.40.2", host = "example.com"))
                .put(videoFormat(100, 720, 30, 2_500_000, "avc1.64001F", "mp4a.40.2", query = "?n=unresolved")),
        )

        val options = repository.downloadOptions(video, streaming, DownloadStreamPreferences(720, true, true), audioOnly = false)

        assertEquals(listOf(22, 18), options.map { it.stream.itag })
        assertEquals("MP4", options.first().container)
        assertEquals("avc1.64001F", options.first().stream.videoCodec)
        assertTrue(options.all { it.stream.videoHeight <= 720 && it.stream.fps <= 30.0 })
        assertTrue(options.all { it.stream.isMuxed && !it.audioOnly })
    }

    @Test
    fun audioOptionsAreSortedByBitrateAndRejectUnresolvedOrUntrustedUrls() {
        val streaming = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(audioFormat(140, 128_000, "mp4a.40.2", "audio/mp4"))
                .put(audioFormat(251, 160_000, "opus", "audio/webm"))
                .put(audioFormat(999, 320_000, "mp4a.40.2", "audio/mp4", query = "?n=unresolved"))
                .put(audioFormat(998, 320_000, "mp4a.40.2", "audio/mp4", host = "example.com")),
        )

        val options = repository.downloadOptions(video, streaming, DownloadStreamPreferences(), audioOnly = true)

        assertEquals(listOf(251, 140), options.map { it.stream.itag })
        assertTrue(options.all { it.audioOnly && !it.stream.isMuxed })
        assertEquals(160, options.first().bitrateKbps)
        assertEquals("WebM", options.first().container)
        assertFalse(options.any { it.stream.uri.contains("unresolved") })
    }

    private fun videoFormat(
        itag: Int,
        height: Int,
        fps: Int,
        bitrate: Long,
        videoCodec: String,
        audioCodec: String,
        container: String = "mp4",
        host: String = "rr1---sn.googlevideo.com",
        query: String = "?expire=123",
    ) = JSONObject()
        .put("itag", itag)
        .put("mimeType", "video/$container; codecs=\"$videoCodec, $audioCodec\"")
        .put("audioQuality", "AUDIO_QUALITY_MEDIUM")
        .put("height", height)
        .put("fps", fps)
        .put("bitrate", bitrate)
        .put("url", "https://$host/videoplayback$query")

    private fun audioFormat(
        itag: Int,
        bitrate: Long,
        codec: String,
        mime: String,
        host: String = "rr1---sn.googlevideo.com",
        query: String = "?expire=123",
    ) = JSONObject()
        .put("itag", itag)
        .put("mimeType", "$mime; codecs=\"$codec\"")
        .put("bitrate", bitrate)
        .put("url", "https://$host/videoplayback$query")
}
