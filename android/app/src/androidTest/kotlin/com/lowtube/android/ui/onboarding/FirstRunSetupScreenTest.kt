package com.lowtube.android.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.R
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.FirstRunSetupSelection
import com.lowtube.android.data.PlaybackStreamPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunSetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appliesLanguageScaleThemeAndQualityChoices() {
        val restoration = StateRestorationTester(compose)
        var previewScale = 1.0f
        var applied: FirstRunSetupSelection? = null
        restoration.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackPreferences = PlaybackStreamPreferences(),
                downloadPreferences = DownloadStreamPreferences(),
                onPreviewUiScale = { previewScale = it },
                onApply = { applied = it },
                onSkip = {},
            )
        }
        compose.onNodeWithText("Українська").performScrollTo().performClick()
        compose.onNodeWithText("115%").performScrollTo().performClick()
        compose.onNodeWithTag(FIRST_RUN_PLAYBACK_H264_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(FIRST_RUN_PLAYBACK_AUDIO_ONLY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(FIRST_RUN_DOWNLOAD_FPS30_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(FIRST_RUN_DOWNLOAD_QUALITY_TAG).performScrollTo().performClick()
        compose.onNodeWithText("480p").performClick()
        compose.runOnIdle { previewScale = 1.0f }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(FIRST_RUN_FINISH_TAG).performClick()
        compose.runOnIdle {
            assertEquals(1.15f, previewScale)
            assertEquals(
                FirstRunSetupSelection(
                    language = "uk",
                    uiScale = 1.15f,
                    darkTheme = true,
                    playback = PlaybackStreamPreferences(maxHeight = 720, h264Only = false, fps30Only = true, audioOnly = true),
                    downloads = DownloadStreamPreferences(maxHeight = 480, h264Only = true, fps30Only = true, audioBitrateKbps = 192),
                ),
                applied,
            )
        }
    }

    @Test fun skipRemainsAnExplicitAction() {
        var skipped = false
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackPreferences = PlaybackStreamPreferences(),
                downloadPreferences = DownloadStreamPreferences(),
                onPreviewUiScale = {},
                onApply = { error("Skip should not apply the setup draft.") },
                onSkip = { skipped = true },
            )
        }
        compose.onNodeWithTag(FIRST_RUN_SKIP_TAG).performClick()
        compose.runOnIdle { assertTrue(skipped) }
    }

    @Test fun compactLayoutKeepsSetupActionsVisibleWhilePreferencesScroll() {
        assumeTrue("Compact phone layout", compose.activity.resources.configuration.smallestScreenWidthDp < 600)
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackPreferences = PlaybackStreamPreferences(),
                downloadPreferences = DownloadStreamPreferences(),
                onPreviewUiScale = {},
                onApply = {},
                onSkip = {},
            )
        }

        compose.onNodeWithTag(FIRST_RUN_SKIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_FINISH_TAG).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.playback)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_SKIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_FINISH_TAG).assertIsDisplayed()
    }

    @Test fun downloadFolderChoiceIsAvailableDuringFirstRun() {
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackPreferences = PlaybackStreamPreferences(),
                downloadPreferences = DownloadStreamPreferences(),
                onPreviewUiScale = {},
                onApply = {},
                onSkip = {},
            )
        }

        compose.onNodeWithText(compose.activity.getString(R.string.download_location_title))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.choose_download_folder))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test fun wideLayoutKeepsBothSettingGroupsAndActionsVisible() {
        assumeTrue(
            "Run with -e runWideFirstRunSetup true on a window wider than 800 dp",
            InstrumentationRegistry.getArguments().getString("runWideFirstRunSetup") == "true",
        )
        var applied: FirstRunSetupSelection? = null
        compose.setContent {
            FirstRunSetupScreen(
                language = "en",
                uiScale = 1.0f,
                darkTheme = true,
                playbackPreferences = PlaybackStreamPreferences(),
                downloadPreferences = DownloadStreamPreferences(),
                onPreviewUiScale = {},
                onApply = { applied = it },
                onSkip = {},
            )
        }

        compose.onNodeWithText(compose.activity.getString(R.string.language)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.maximum_quality)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_PLAYBACK_H264_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_PLAYBACK_FPS30_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_PLAYBACK_AUDIO_ONLY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.maximum_download_quality)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_DOWNLOAD_H264_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_DOWNLOAD_FPS30_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.download_location_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_SKIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIRST_RUN_FINISH_TAG).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                FirstRunSetupSelection("en", 1.0f, true, PlaybackStreamPreferences(), DownloadStreamPreferences()),
                applied,
            )
        }
    }
}
