package com.lowtube.android.ui.playback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.ui.theme.LowTubeTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelFollowControlTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun labelTracksFollowStateAndTapInvokesAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val following = mutableStateOf(false)
        var clicks = 0

        composeTestRule.setContent {
            LowTubeTheme {
                ChannelFollowControl(isFollowing = following.value) {
                    clicks++
                    following.value = !following.value
                }
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.follow)).assertExists().performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.followed)).assertExists().performClick()
        composeTestRule.runOnIdle { assertEquals(2, clicks) }
        composeTestRule.onNodeWithText(context.getString(R.string.follow)).assertExists()
    }
}
