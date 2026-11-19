package com.lowtube.android.ui.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import com.lowtube.android.R
import com.lowtube.android.ui.theme.LowTubeTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackSeekGestureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun leftAndRightDoubleTapRequestTenSecondSeek() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seeks = mutableStateListOf<Long>()
        var shownControls = 0
        composeTestRule.setContent {
            LowTubeTheme {
                Box(Modifier.fillMaxSize()) {
                    PlaybackSeekGestureOverlay(
                        onShowControls = { shownControls++ },
                        onSeek = seeks::add,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.seek_backward_10_seconds))
            .performTouchInput { doubleClick() }
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.seek_forward_10_seconds))
            .performTouchInput { doubleClick() }

        composeTestRule.runOnIdle {
            assertEquals(listOf(-10_000L, 10_000L), seeks.toList())
            assertEquals(0, shownControls)
        }
    }
}
