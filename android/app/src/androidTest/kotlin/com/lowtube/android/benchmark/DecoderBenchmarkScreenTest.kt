package com.lowtube.android.benchmark

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lowtube.android.R
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Rule
import org.junit.Test

class DecoderBenchmarkScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sourceMustBeSelectedBeforeRunningAndResultsBeforeExport() {
        composeTestRule.setContent {
            LowTubeTheme {
                DecoderBenchmarkScreen(onBack = {})
            }
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark_select_video))
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark_run))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark_compare))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark_export))
            .assertDoesNotExist()

        if (!DecoderBenchmarkEngine.isHardwareProfileAvailable()) {
            composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.decoder_benchmark_hardware_profile))
                .assertIsNotEnabled()
        }
    }
}
