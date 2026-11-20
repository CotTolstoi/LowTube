package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.VideoItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that formats with an already-resolved YouTube n parameter remain downloadable. */
@RunWith(AndroidJUnit4::class)
class InnerTubeDownloadOptionsTest {
    @Test
    fun keepsDirectFormatAfterNParameterWasTransformed() {
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val streaming = JSONObject(
            """
            {
              "formats": [{
                "url": "https://r1.googlevideo.com/videoplayback?itag=18&n=transformed-value",
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                "audioQuality": "AUDIO_QUALITY_LOW",
                "height": 360,
                "fps": 30,
                "itag": 18,
                "bitrate": 500000
              }]
            }
            """.trimIndent(),
        )

        val options = InnerTubeSearchRepository().downloadOptions(
            video,
            streaming,
            DownloadStreamPreferences(),
            audioOnly = false,
        )

        assertEquals(2, options.size)
        assertTrue(options.any { it.container == "MKV" && it.key.endsWith("|container=mkv") })
        assertTrue(options.single { it.container != "MKV" }.stream.uri.contains("n=transformed-value"))
    }

    @Test
    fun preservesSelectedClientUserAgentOnDownloadStream() {
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val streaming = JSONObject(
            """
            {
              "formats": [{
                "url": "https://r1.googlevideo.com/videoplayback?itag=18&n=transformed-value",
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                "audioQuality": "AUDIO_QUALITY_LOW",
                "height": 360,
                "fps": 30,
                "itag": 18,
                "bitrate": 500000
              }]
            }
            """.trimIndent(),
        )
        val mwebUserAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X)"

        val options = InnerTubeSearchRepository().downloadOptions(
            video,
            streaming,
            DownloadStreamPreferences(),
            audioOnly = false,
            userAgent = mwebUserAgent,
        )

        assertTrue(options.all { it.stream.userAgent == mwebUserAgent })
    }
}
