package com.lowtube.android.ui.main

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
}
