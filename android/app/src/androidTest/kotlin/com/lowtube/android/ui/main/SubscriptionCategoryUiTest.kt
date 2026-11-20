package com.lowtube.android.ui.main

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lowtube.android.model.VideoItem
import com.lowtube.android.data.LocalLibraryRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubscriptionCategoryUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun categoryChipFiltersSubscriptionFeed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-category-ui-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val scienceChannelId = "UC" + "s".repeat(22)
        val otherChannelId = "UC" + "o".repeat(22)
        try {
            repository.follow(scienceChannelId, "Science channel", "https://www.youtube.com/channel/$scienceChannelId")
            repository.follow(otherChannelId, "Other channel", "https://www.youtube.com/channel/$otherChannelId")
            repository.setChannelCategory(scienceChannelId, "Science")
            repository.replaceChannelFeed(
                scienceChannelId,
                listOf(VideoItem("science0001", "Science upload", "Science channel", scienceChannelId, url = "https://www.youtube.com/watch?v=science0001")),
                1L,
            )
            repository.replaceChannelFeed(
                otherChannelId,
                listOf(VideoItem("other000001", "Other upload", "Other channel", otherChannelId, url = "https://www.youtube.com/watch?v=other000001")),
                2L,
            )

            composeTestRule.setContent {
                MaterialTheme {
                    SubscriptionsTab(
                        repository = repository,
                        accountEmail = "",
                        refreshRevision = 0,
                        youtubeSyncBusy = false,
                        youtubeSyncMessage = "",
                        onSyncYouTubeSubscriptions = {},
                        onAddChannel = {},
                        onBrowseChannel = {},
                        onPlay = {},
                    )
                }
            }

            composeTestRule.onNodeWithText("Science upload").assertExists()
            composeTestRule.onNodeWithText("Other upload").assertExists()
            composeTestRule.onNodeWithText("Science", useUnmergedTree = true).performClick()
            composeTestRule.onNodeWithText("Science upload").assertExists()
            assertTrue(composeTestRule.onAllNodesWithText("Other upload").fetchSemanticsNodes().isEmpty())
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun subscriptionFeedUsesAdaptiveGridWithoutDroppingItems() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-subscriptions-grid-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val channelId = "UC" + "g".repeat(22)
        try {
            repository.follow(channelId, "Grid channel", "https://www.youtube.com/channel/$channelId")
            repository.replaceChannelFeed(
                channelId,
                listOf(
                    VideoItem("gridvideo01", "Grid first video", "Grid channel", channelId, url = "https://www.youtube.com/watch?v=gridvideo01"),
                    VideoItem("gridvideo02", "Grid second video", "Grid channel", channelId, url = "https://www.youtube.com/watch?v=gridvideo02"),
                ),
                1L,
            )

            composeTestRule.setContent {
                MaterialTheme {
                    SubscriptionsTab(
                        repository = repository,
                        accountEmail = "",
                        refreshRevision = 0,
                        youtubeSyncBusy = false,
                        youtubeSyncMessage = "",
                        onSyncYouTubeSubscriptions = {},
                        onAddChannel = {},
                        onBrowseChannel = {},
                        onPlay = {},
                    )
                }
            }
            composeTestRule.waitForIdle()

            val density = context.resources.displayMetrics.density
            val grid = composeTestRule.onNodeWithTag(SUBSCRIPTIONS_FEED_GRID_TEST_TAG).fetchSemanticsNode().boundsInRoot
            val first = composeTestRule.onNodeWithText("Grid first video").fetchSemanticsNode().boundsInRoot
            val second = composeTestRule.onNodeWithText("Grid second video").fetchSemanticsNode().boundsInRoot
            if (grid.width / density >= 760f) {
                assertTrue("Wide subscriptions should use multiple columns", second.left > first.left && second.top == first.top)
            } else {
                assertTrue("Compact subscriptions should retain one column", second.top >= first.bottom)
            }
            composeTestRule.onNodeWithText("Grid first video").assertExists()
            composeTestRule.onNodeWithText("Grid second video").assertExists()
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
}
