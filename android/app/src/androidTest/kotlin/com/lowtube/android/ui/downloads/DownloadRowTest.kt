package com.lowtube.android.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadRowTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pausedDownloadShowsSavedProgressAndAllowsRetry() {
        val record = DownloadRecord(
            id = 42L,
            title = "Paused transfer fixture",
            status = DownloadStatus.PAUSED,
            reason = "",
            downloadedBytes = 32L,
            totalBytes = 64L,
            localUri = "",
            mimeType = "video/mp4",
        )
        var retryRequested = false

        composeTestRule.setContent {
            LowTubeTheme {
                DownloadRow(
                    record = record,
                    retrying = false,
                    onOpen = {},
                    onRemove = {},
                    onRetry = { retryRequested = true },
                )
            }
        }

        composeTestRule.onNodeWithText(record.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.download_paused)).assertIsDisplayed()
        composeTestRule.onNodeWithText("50% • 32 B / 64 B").assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.retry)).performClick()

        assertTrue("Paused download retry action was not forwarded", retryRequested)
    }

    @Test
    fun completedAudioDownloadOffersLowTubePlayback() {
        val record = DownloadRecord(
            id = 43L,
            title = "Downloaded audio fixture",
            status = DownloadStatus.SUCCESSFUL,
            reason = "",
            downloadedBytes = 4_096L,
            totalBytes = 4_096L,
            localUri = "content://media/external/downloads/43",
            mimeType = "audio/mpeg",
        )
        var playbackRequested = false

        composeTestRule.setContent {
            LowTubeTheme {
                DownloadRow(
                    record = record,
                    retrying = false,
                    onPlayInLowTube = { playbackRequested = true },
                    onOpen = {},
                    onRemove = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.play_download_in_lowtube))
            .assertIsDisplayed()
            .performClick()
        assertTrue("Completed audio download should offer LowTube playback", playbackRequested)
    }
}
