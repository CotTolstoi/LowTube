package com.lowtube.android.ui.main

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveMainNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainNavigationMatchesCurrentWindowWidth() {
        val settings = compose.activity.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE)
        val hadFirstRunValue = settings.contains("first_run_complete")
        val previousFirstRunValue = settings.getBoolean("first_run_complete", false)
        try {
            check(settings.edit().putBoolean("first_run_complete", true).commit())
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            val density = compose.activity.resources.displayMetrics.density
            val availableWidthDp = compose.onRoot().fetchSemanticsNode().boundsInRoot.width / density
            val railCount = compose.onAllNodesWithTag(MAIN_TAB_RAIL_TEST_TAG).fetchSemanticsNodes().size
            val bottomCount = compose.onAllNodesWithTag(MAIN_TAB_BOTTOM_BAR_TEST_TAG).fetchSemanticsNodes().size
            if (availableWidthDp >= 600f) {
                assertEquals(1, railCount)
                assertEquals(0, bottomCount)
            } else {
                assertEquals(0, railCount)
                assertEquals(1, bottomCount)
            }
        } finally {
            val editor = settings.edit()
            if (hadFirstRunValue) editor.putBoolean("first_run_complete", previousFirstRunValue)
            else editor.remove("first_run_complete")
            check(editor.commit())
            compose.activityRule.scenario.recreate()
        }
    }
}
