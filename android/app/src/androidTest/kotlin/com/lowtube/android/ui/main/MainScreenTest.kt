package com.lowtube.android.ui.main

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import com.lowtube.android.ui.onboarding.FIRST_RUN_DOWNLOAD_FPS30_TAG
import com.lowtube.android.ui.onboarding.FIRST_RUN_DOWNLOAD_QUALITY_TAG
import com.lowtube.android.ui.onboarding.FIRST_RUN_FINISH_TAG
import com.lowtube.android.ui.onboarding.FIRST_RUN_PLAYBACK_H264_TAG
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Smoke coverage for the actual app entry screen (catalog navigation, no playback). */
class MainScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainScreenShowsPrimaryNavigation() {
        assertTrue(
            composeTestRule.onAllNodesWithText(composeTestRule.activity.getString(R.string.search_tab))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.recommendations_tab)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.subscriptions_tab)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.profile)).assertExists()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.more_menu), substring = true).assertExists()
    }

    @Test
    fun primaryTabsAndMoreMenuRemainReachableOnApi23() {
        val activity = composeTestRule.activity
        val tabLabels = listOf(
            R.string.search_tab,
            R.string.recommendations_tab,
            R.string.subscriptions_tab,
            R.string.profile,
        )

        tabLabels.forEach { labelId ->
            val tab = hasText(activity.getString(labelId)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            composeTestRule.onNode(tab).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(tab).assertIsSelected()
        }

        composeTestRule.onNodeWithText(activity.getString(R.string.more_menu), substring = true).performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.library)).assertExists()
        composeTestRule.onNodeWithText(activity.getString(R.string.watch_queue)).assertExists()
        composeTestRule.onNodeWithText(activity.getString(R.string.downloads)).assertExists()
        composeTestRule.onNodeWithText(activity.getString(R.string.settings)).assertExists()
    }

    @Test
    fun settingsBackRestoresSearchTabSelection() {
        val search = composeTestRule.activity.getString(R.string.search_tab)
        val settings = composeTestRule.activity.getString(R.string.settings)
        val searchTab = hasText(search) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeTestRule.onNode(searchTab).performClick()
        composeTestRule.onNode(searchTab).assertIsSelected()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.more_menu), substring = true).performClick()
        composeTestRule.onNodeWithText(settings).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.back)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(searchTab).assertIsSelected()
    }

    @Test
    fun backDispatcherRestoresSearchTabSelection() {
        val search = composeTestRule.activity.getString(R.string.search_tab)
        val searchTab = hasText(search) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeTestRule.onNode(searchTab).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.more_menu), substring = true).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.settings)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(searchTab).assertIsSelected()
    }

    @Test
    fun setupCanBeReopenedFromSettingsAndSavesStreamPreferences() {
        val activity = composeTestRule.activity
        val settings = activity.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE)
        val keys = listOf("first_run_complete", "playback_h264", "download_fps30", "download_height")
        val previous = keys.associateWith { key -> settings.all[key] }
        val initiallyComplete = settings.getBoolean("first_run_complete", false)
        val previousPlaybackH264 = settings.getBoolean("playback_h264", true)
        val previousDownloadFps30 = settings.getBoolean("download_fps30", false)
        val previousDownloadHeight = settings.getInt("download_height", 720)
        val selectedDownloadHeight = if (previousDownloadHeight == 480) 720 else 480

        fun reopenSetupFromSettings() {
            val currentActivity = composeTestRule.activity
            composeTestRule.onNodeWithText(currentActivity.getString(R.string.more_menu), substring = true).performClick()
            composeTestRule.onNodeWithText(currentActivity.getString(R.string.settings)).performClick()
            composeTestRule.onNodeWithText(currentActivity.getString(R.string.onboarding_run_again))
                .performScrollTo()
                .performClick()
            composeTestRule.onNodeWithText(currentActivity.getString(R.string.onboarding_title)).assertExists()
        }

        try {
            if (!initiallyComplete) {
                composeTestRule.onNodeWithText(activity.getString(R.string.onboarding_title)).assertExists()
                composeTestRule.onNodeWithTag(FIRST_RUN_FINISH_TAG).performScrollTo().performClick()
                composeTestRule.waitUntil(10_000L) {
                    settings.getBoolean("first_run_complete", false) &&
                        composeTestRule.onAllNodesWithText(composeTestRule.activity.getString(R.string.search_tab)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            reopenSetupFromSettings()

            composeTestRule.onNodeWithTag(FIRST_RUN_PLAYBACK_H264_TAG).performScrollTo().performClick()
            composeTestRule.onNodeWithTag(FIRST_RUN_DOWNLOAD_FPS30_TAG).performScrollTo().performClick()
            composeTestRule.onNodeWithTag(FIRST_RUN_DOWNLOAD_QUALITY_TAG).performScrollTo().performClick()
            composeTestRule.onNodeWithText(activity.getString(R.string.quality_value, selectedDownloadHeight)).performClick()
            composeTestRule.onNodeWithTag(FIRST_RUN_FINISH_TAG).performScrollTo().performClick()

            composeTestRule.waitUntil(10_000L) {
                settings.getBoolean("first_run_complete", false) && settings.getBoolean("playback_h264", true) == !previousPlaybackH264 &&
                    settings.getBoolean("download_fps30", false) == !previousDownloadFps30 &&
                    settings.getInt("download_height", 720) == selectedDownloadHeight
            }
            composeTestRule.waitUntil(10_000L) {
                composeTestRule.onAllNodesWithText(activity.getString(R.string.search_tab)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(!previousPlaybackH264, settings.getBoolean("playback_h264", true))
            assertEquals(!previousDownloadFps30, settings.getBoolean("download_fps30", false))
            assertEquals(selectedDownloadHeight, settings.getInt("download_height", 720))
        } finally {
            val editor = settings.edit()
            keys.forEach { key ->
                editor.remove(key)
                when (val value = previous[key]) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            assertEquals(previous, keys.associateWith { key -> settings.all[key] })
            composeTestRule.runOnUiThread { composeTestRule.activity.recreate() }
            composeTestRule.waitForIdle()
        }
    }

}
