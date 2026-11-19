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
}
