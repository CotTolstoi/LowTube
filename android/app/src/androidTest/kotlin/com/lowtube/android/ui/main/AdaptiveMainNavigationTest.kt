package com.lowtube.android.ui.main

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        compose.waitForIdle()
        val expectedMode = InstrumentationRegistry.getArguments().getString("mainNavigationMode")
        val railCount = compose.onAllNodesWithTag(MAIN_TAB_RAIL_TEST_TAG).fetchSemanticsNodes().size
        val bottomCount = compose.onAllNodesWithTag(MAIN_TAB_BOTTOM_BAR_TEST_TAG).fetchSemanticsNodes().size
        when (expectedMode) {
            "rail" -> { assertEquals(1, railCount); assertEquals(0, bottomCount) }
            "bottom" -> { assertEquals(0, railCount); assertEquals(1, bottomCount) }
            else -> error("Pass -e mainNavigationMode rail or bottom for this acceptance test.")
        }
    }
}
