package com.lowtube.android.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.R
import com.lowtube.android.model.ChannelHeader
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelHeaderCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun headerShowsChannelDetailsAndInvokesSubscribeAction() {
        var toggleCount = 0
        composeRule.setContent {
            LowTubeTheme {
                ChannelHeaderCard(
                    header = ChannelHeader(
                        channelId = "UCabcdefghijklmnopqrstuv",
                        title = "Science Channel",
                        subscriberCountText = "1.2M subscribers",
                        description = "Public science videos.",
                    ),
                    isFollowing = false,
                    onToggleFollow = { toggleCount++ },
                )
            }
        }

        composeRule.onNodeWithTag(CHANNEL_HEADER_TEST_TAG).assertExists()
        composeRule.onNodeWithText("Science Channel").assertExists()
        composeRule.onNodeWithText("1.2M subscribers").assertExists()
        composeRule.onNodeWithText("Public science videos.").assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.follow)).performClick()
        composeRule.runOnIdle { assertEquals(1, toggleCount) }
    }
}
