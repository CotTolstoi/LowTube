package com.lowtube.android.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.VideoItem
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadFormatPickerDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingNativeFormatReturnsExactOption() {
        val video = VideoItem("abcdefghijk", "A test video", url = "https://www.youtube.com/watch?v=abcdefghijk")
        val options = listOf(
            option(video, 22, 720, 30.0, "avc1.64001F"),
            option(video, 18, 360, 30.0, "avc1.42001E"),
        )
        var selected: DownloadFormatOption? = null

        composeTestRule.setContent {
            LowTubeTheme {
                DownloadFormatPickerDialog(
                    options = options,
                    audioOnly = false,
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }

        val label = composeTestRule.activity.getString(
            R.string.download_video_format,
            360,
            30,
            "avc1.42001E",
            "mp4a.40.2",
            "MP4",
        )
        composeTestRule.onNodeWithText(label).performClick()
        assertEquals(options[1], selected)
    }

    private fun option(video: VideoItem, itag: Int, height: Int, fps: Double, codec: String) =
        DownloadFormatOption(
            key = "$itag|video/mp4|false",
            stream = ResolvedVideoStream(
                uri = "https://rr1---sn.googlevideo.com/videoplayback?itag=$itag",
                mimeType = "video/mp4",
                video = video,
                userAgent = "test-agent",
                durationMs = 120_000,
                videoHeight = height,
                fps = fps,
                videoCodec = codec,
                audioCodec = "mp4a.40.2",
                itag = itag,
                isMuxed = true,
            ),
            bitrateKbps = 900,
            container = "MP4",
        )
}
