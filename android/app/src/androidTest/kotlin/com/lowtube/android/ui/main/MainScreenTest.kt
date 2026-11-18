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
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import org.junit.Assert.assertTrue
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

}
