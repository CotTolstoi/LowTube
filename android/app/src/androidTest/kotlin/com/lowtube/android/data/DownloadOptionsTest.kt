package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.Uri
import android.os.Build
import com.lowtube.android.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOptionsTest {
    private val repository = InnerTubeSearchRepository()
    private val video = VideoItem(id = "abcdefghijk", title = "Test video", url = "https://www.youtube.com/watch?v=abcdefghijk")

    @Test
    fun videoOptionsHonorLimitsAndKeepResolvedNParameter() {
        val streaming = JSONObject().put(
            "formats",
            JSONArray()
                .put(videoFormat(22, 720, 30, 2_000_000, "avc1.64001F", "mp4a.40.2"))
                .put(videoFormat(18, 360, 30, 800_000, "avc1.42001E", "mp4a.40.2"))
                .put(videoFormat(23, 720, 60, 3_000_000, "avc1.640020", "mp4a.40.2"))
                .put(videoFormat(37, 1080, 30, 5_000_000, "avc1.640028", "mp4a.40.2"))
                .put(videoFormat(45, 720, 30, 2_500_000, "vp9", "opus", container = "webm"))
                .put(videoFormat(99, 720, 30, 2_500_000, "avc1.64001F", "mp4a.40.2", host = "example.com"))
                .put(videoFormat(100, 720, 30, 2_500_000, "avc1.64001F", "mp4a.40.2", query = "?n=transformed")),
        )

        val options = repository.downloadOptions(video, streaming, DownloadStreamPreferences(720, true, true), audioOnly = false)

        val sourceOptions = options.filter { it.container != "MKV" }
        val mkvOptions = options.filter { it.container == "MKV" }
        assertEquals(listOf(100, 22, 18), sourceOptions.map { it.stream.itag })
        assertEquals(listOf(100, 22, 18), mkvOptions.map { it.stream.itag })
        assertEquals("MP4", sourceOptions.first().container)
        assertTrue(mkvOptions.all { it.key.endsWith("|container=mkv") })
        assertEquals("MKV", mkvOptions.first().container)
        assertEquals("avc1.64001F", options.first().stream.videoCodec)
        assertTrue(options.all { it.stream.videoHeight <= 720 && it.stream.fps <= 30.0 })
        assertTrue(options.all { it.stream.isMuxed && !it.audioOnly })
        assertEquals("transformed", Uri.parse(options.first().stream.uri).getQueryParameter("n"))
    }

    @Test
    fun audioOptionsAreSortedByBitrateAndKeepResolvedNParameter() {
        val streaming = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(audioFormat(140, 128_000, "mp4a.40.2", "audio/mp4"))
                .put(audioFormat(251, 160_000, "opus", "audio/webm"))
                .put(audioFormat(999, 320_000, "mp4a.40.2", "audio/mp4", query = "?n=transformed"))
                .put(audioFormat(998, 320_000, "mp4a.40.2", "audio/mp4", host = "example.com")),
        )

        val options = repository.downloadOptions(video, streaming, DownloadStreamPreferences(), audioOnly = true)

        assertEquals(listOf(999, 251, 140), options.map { it.stream.itag })
        assertTrue(options.all { it.audioOnly && !it.stream.isMuxed })
        assertEquals(320, options.first().bitrateKbps)
        assertEquals("M4A", options.first().container)
        assertEquals("transformed", Uri.parse(options.first().stream.uri).getQueryParameter("n"))
    }

    @Test
    fun separateWebmTracksUseCodecsSupportedByThisAndroidVersion() {
        val streaming = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(videoOnlyFormat(247, 720, 30, 2_000_000, "vp09.00.31.08"))
                .put(videoOnlyFormat(244, 480, 30, 1_000_000, "vp8"))
                .put(audioFormat(251, 160_000, "opus", "audio/webm"))
                .put(audioFormat(250, 70_000, "vorbis", "audio/webm")),
        )

        val options = repository.downloadOptions(
            video,
            streaming,
            DownloadStreamPreferences(h264Only = false),
            audioOnly = false,
        )

        val sourceOptions = options.filter { it.container != "MKV" }
        val mkvOptions = options.filter { it.container == "MKV" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            assertEquals(listOf("vp8"), sourceOptions.map { it.stream.videoCodec })
        } else {
            assertTrue(sourceOptions.any { it.stream.videoCodec.startsWith("vp09") })
        }
        assertTrue(sourceOptions.isNotEmpty())
        assertTrue(sourceOptions.all { it.container == "WebM" && it.requiresMuxing })
        assertTrue(mkvOptions.isNotEmpty())
        assertTrue(mkvOptions.all { it.requiresMuxing && it.key.endsWith("|container=mkv") })
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            assertTrue(sourceOptions.all { it.audioStream?.audioCodec == "vorbis" })
        } else {
            assertTrue(sourceOptions.all { it.audioStream?.audioCodec == "opus" })
        }
    }

    @Test
    fun h264OnlyPreferenceDoesNotOfferWebmSeparateTracks() {
        val streaming = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(videoOnlyFormat(244, 480, 30, 1_000_000, "vp8"))
                .put(audioFormat(250, 70_000, "vorbis", "audio/webm")),
        )

        val options = repository.downloadOptions(
            video,
            streaming,
            DownloadStreamPreferences(h264Only = true),
            audioOnly = false,
        )

        assertTrue(options.isEmpty())
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

    private fun videoOnlyFormat(
        itag: Int,
        height: Int,
        fps: Int,
        bitrate: Long,
        codec: String,
    ) = JSONObject()
        .put("itag", itag)
        .put("mimeType", "video/webm; codecs=\"$codec\"")
        .put("height", height)
        .put("fps", fps)
        .put("bitrate", bitrate)
        .put("url", "https://rr1---sn.googlevideo.com/videoplayback?expire=123")
}
