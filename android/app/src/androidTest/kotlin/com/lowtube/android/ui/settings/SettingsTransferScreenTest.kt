package com.lowtube.android.ui.settings

import androidx.activity.ComponentActivity
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.lowtube.android.R
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.data.ResourceUsageSampler
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class SettingsTransferScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsUsesTwoColumnsOnlyForWideWindows() {
        showSettings()
        composeTestRule.waitForIdle()
        val wideWindow = InstrumentationRegistry.getArguments().getString("settingsLayout") == "twoColumns"
        val singleColumnCount = composeTestRule.onAllNodesWithTag(SETTINGS_SINGLE_COLUMN_TEST_TAG).fetchSemanticsNodes().size
        val leftPaneNodes = composeTestRule.onAllNodesWithTag(SETTINGS_LEFT_PANE_TEST_TAG).fetchSemanticsNodes()
        val rightPaneNodes = composeTestRule.onAllNodesWithTag(SETTINGS_RIGHT_PANE_TEST_TAG).fetchSemanticsNodes()
        if (wideWindow) {
            assertEquals(0, singleColumnCount)
            assertEquals(1, leftPaneNodes.size)
            assertEquals(1, rightPaneNodes.size)
            assertTrue("Settings columns should be side by side", rightPaneNodes.single().boundsInRoot.left >= leftPaneNodes.single().boundsInRoot.right)
        } else {
            assertEquals(1, singleColumnCount)
            assertEquals(0, leftPaneNodes.size)
            assertEquals(0, rightPaneNodes.size)
        }
    }

    @Test
    fun showsImportAndExportActions() {
        showSettings()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.import_settings)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.export_settings)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.local_library_transfer)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.export_local_library)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.import_local_library)).assertExists()
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
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.copy_diagnostic_report)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.export_diagnostic_report)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.subscription_feed_pace)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.page_size)).assertExists()
    }

    @Test
    fun copiesOnlySanitizedDiagnosticFieldsToClipboard() {
        showSettings()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.copy_diagnostic_report))
            .performScrollTo()
            .performClick()

        val copiedMessage = composeTestRule.activity.getString(R.string.diagnostic_report_copied)
        composeTestRule.waitUntil(10_000L) {
            composeTestRule.onAllNodesWithText(copiedMessage).fetchSemanticsNodes().isNotEmpty()
        }

        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val report = clipboard.primaryClip?.getItemAt(0)?.coerceToText(composeTestRule.activity)?.toString().orEmpty()
        assertTrue(report.contains("LowTube Android diagnostics"))
        assertTrue(report.contains("Android:"))
        assertTrue(report.contains("Playback buffer:"))
        assertTrue(report.contains("Download jobs: unavailable"))
        assertTrue(report.contains("excludes account details"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("@"))
    }

    @Test
    fun connectionPresetSelectionNotifiesSettingsOwner() {
        val selectedPreset = AtomicInteger(-1)
        showSettings(onFeedRefreshPresetChanged = { selectedPreset.set(it) })

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.connection_preset_balanced))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.connection_preset_slow)).performClick()
        assertEquals(0, selectedPreset.get())

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.connection_preset_slow)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.connection_preset_fast)).performClick()
        assertEquals(2, selectedPreset.get())
    }

    @Test
    fun pageSizeFieldNotifiesSettingsOwner() {
        val selectedPageSize = AtomicInteger(-1)
        showSettings(onPageSizeChanged = { selectedPageSize.set(it) })

        val pageSizeField = composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.page_size))
            .performScrollTo()
        pageSizeField.performClick()
        pageSizeField.performTextClearance()
        pageSizeField.performTextInput("40")

        assertEquals(40, selectedPageSize.get())
    }

    @Test
    fun showsResourceSampleAfterOnDemandMeasurement() {
        showSettings()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resource_usage_measure))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.resource_usage_sampling)).assertExists()
        val errorMessage = composeTestRule.activity.getString(R.string.resource_usage_error)
        composeTestRule.waitUntil(15_000L) {
            composeTestRule.onAllNodesWithTag("resource_usage_sample_result").fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText(errorMessage).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("resource_usage_sample_result").assertExists()
        composeTestRule.onNodeWithText(errorMessage).assertDoesNotExist()
    }

    @Test
    fun resourceSamplerReturnsPssAndSystemMemoryAfterFiveSeconds() {
        val startMs = SystemClock.elapsedRealtime()
        val sample = runBlocking {
            ResourceUsageSampler.capture(composeTestRule.activity.applicationContext)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startMs

        assertTrue("Resource sample should last at least 4.5 seconds", sample.durationMs >= 4_500L)
        assertTrue("Resource sample should complete within 15 seconds", elapsedMs <= 15_000L)
        assertTrue("System RAM should be reported", sample.totalRamMiB > 0L)
        assertTrue("Process PSS should be reported", sample.pssStartKiB > 0L && sample.pssEndKiB > 0L)
    }

    private fun showSettings(
        onFeedRefreshPresetChanged: (Int) -> Unit = {},
        onPageSizeChanged: (Int) -> Unit = {},
    ) {
        composeTestRule.setContent {
            SettingsScreen(
                onDarkThemeChanged = {},
                onLanguageChanged = {},
                discoveryTopic = "science and technology",
                discoveryLanguage = "any",
                onDiscoveryPreferencesChanged = { _, _ -> },
                onFeedRefreshPresetChanged = onFeedRefreshPresetChanged,
                onPageSizeChanged = onPageSizeChanged,
                playbackPreferences = PlaybackStreamPreferences(),
                onPlaybackPreferencesChanged = {},
            )
        }
    }
}
