package com.lowtube.android.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunSetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appliesLanguageScaleThemeAndQualityChoices() {
        var previewScale = 1.0f
        var applied: List<Any>? = null
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackMaxHeight = 720,
                downloadAudioBitrateKbps = 192,
                onPreviewUiScale = { previewScale = it },
                onApply = { language, scale, dark, height, bitrate -> applied = listOf(language, scale, dark, height, bitrate) },
                onSkip = {},
            )
        }
        compose.onNodeWithText("Українська").performScrollTo().performClick()
        compose.onNodeWithText("115%").performScrollTo().performClick()
        compose.onNodeWithTag(FIRST_RUN_FINISH_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1.15f, previewScale)
            assertEquals(listOf("uk", 1.15f, true, 720, 192), applied)
        }
    }

    @Test fun skipRemainsAnExplicitAction() {
        var skipped = false
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackMaxHeight = 720,
                downloadAudioBitrateKbps = 192,
                onPreviewUiScale = {},
                onApply = { _, _, _, _, _ -> error("Skip should not apply the setup draft.") },
                onSkip = { skipped = true },
            )
        }
        compose.onNodeWithTag(FIRST_RUN_SKIP_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(skipped) }
    }
}
