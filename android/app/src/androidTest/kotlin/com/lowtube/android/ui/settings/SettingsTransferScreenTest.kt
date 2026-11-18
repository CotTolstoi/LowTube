package com.lowtube.android.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lowtube.android.R
import com.lowtube.android.data.PlaybackStreamPreferences
import org.junit.Rule
import org.junit.Test

class SettingsTransferScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsImportAndExportActions() {
        showSettings()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.import_settings)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.export_settings)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.download_settings)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.maximum_download_quality)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.download_h264_only)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.download_fps_30_only)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resume_from_last_position)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.auto_recover_playback)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.auto_play_next)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.preload_next_video)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.playback_buffer_title)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.playback_buffer_seconds)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.playback_buffer_memory_mib)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.playback_back_buffer_mib)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.playback_start_buffer_seconds)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resource_usage_title)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resource_usage_measure)).assertExists()
    }

    @Test
    fun showsResourceSampleAfterOnDemandMeasurement() {
        showSettings()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resource_usage_measure))
            .performScrollTo()
            .performClick()
        val pssMatcher = "LowTube PSS:"
        val errorMessage = composeTestRule.activity.getString(R.string.resource_usage_error)
        composeTestRule.waitUntil(15_000L) {
            composeTestRule.onAllNodesWithText(pssMatcher, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText(errorMessage).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText(pssMatcher, substring = true).assertExists()
        composeTestRule.onNodeWithText("LowTube CPU:", substring = true).assertExists()
        composeTestRule.onNodeWithText("Available system RAM:", substring = true).assertExists()
        composeTestRule.onNodeWithText(errorMessage).assertDoesNotExist()
    }

    private fun showSettings() {
        composeTestRule.setContent {
            SettingsScreen(
                onDarkThemeChanged = {},
                onLanguageChanged = {},
                discoveryTopic = "science and technology",
                discoveryLanguage = "any",
                onDiscoveryPreferencesChanged = { _, _ -> },
                playbackPreferences = PlaybackStreamPreferences(),
                onPlaybackPreferencesChanged = {},
            )
        }
    }
}
