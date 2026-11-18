package com.lowtube.android.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.LocalSearchHistoryRepository
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.ui.theme.LowTubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentSearchSuggestionsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var history: LocalSearchHistoryRepository
    private lateinit var library: LocalLibraryRepository
    private lateinit var databaseName: String

    @Before
    fun prepareIsolatedRepositories() {
        history = LocalSearchHistoryRepository(composeTestRule.activity, "android_test_recent_searches")
        history.clear()
        databaseName = "search_suggestions_${System.nanoTime()}.db"
        library = LocalLibraryRepository(composeTestRule.activity, databaseName)
        history.record("space astronomy")
        history.record("classical music")
        history.record("https://www.youtube.com/watch?v=6kp91gVA9To")
    }

    @After
    fun cleanIsolatedRepositories() {
        if (this::history.isInitialized) history.clear()
        if (this::library.isInitialized) {
            library.close()
            composeTestRule.activity.deleteDatabase(databaseName)
        }
    }

    @Test
    fun filtersRecentTextSearchesAndClearsThem() {
        assertTrue("URL searches must not be stored", history.recent().none { it.startsWith("https://") })
        showSearch()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.search_hint)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.recent_searches)).assertExists()
        composeTestRule.onNodeWithText("space astronomy", substring = true).assertExists()
        composeTestRule.onNodeWithText("classical music", substring = true).assertExists()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.search_hint)).performTextInput("space")
        composeTestRule.onNodeWithText("space astronomy", substring = true).assertExists()
        composeTestRule.onNodeWithText("classical music", substring = true).assertDoesNotExist()

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.clear_recent_searches)).performClick()
        composeTestRule.onNodeWithText("space astronomy", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.recent_searches)).assertDoesNotExist()
    }

    private fun showSearch() {
        val activity = composeTestRule.activity
        composeTestRule.setContent {
            LowTubeTheme {
                LowTubeSearchScreen(
                    googleAccountName = "",
                    googleAccountEmail = "",
                    googleAccountMessage = "",
                    googleSignInBusy = false,
                    youtubeSyncBusy = false,
                    youtubeSyncMessage = "",
                    onGoogleSignIn = {},
                    onGoogleSignOut = {},
                    onSyncYouTubeSubscriptions = {},
                    onOpenSettings = {},
                    onOpenLibrary = {},
                    selectedTab = "search",
                    onSelectedTabChanged = {},
                    miniPlayerVideo = null,
                    onOpenMiniPlayer = {},
                    onOpenQueue = {},
                    onOpenDownloads = {},
                    onPlay = {},
                    onAddToQueue = {},
                    onResolveDownloadOptions = { _, _, complete -> complete(Result.success(emptyList<DownloadFormatOption>())) },
                    onDownload = { _, _ -> },
                    onSaveVideo = {},
                    onSaveToPlaylist = { _, _ -> },
                    localPlaylists = emptyList(),
                    channelBrowseRequest = null,
                    onChannelBrowseConsumed = {},
                    onToggleFollow = { true },
                    isFollowing = { false },
                    discoveryTopic = "test topic",
                    discoveryLanguage = "any",
                    libraryRepository = library,
                    libraryRevision = 0,
                    onAddChannel = {},
                    onBrowseChannel = {},
                    searchHistoryRepository = history,
                )
            }
        }
    }
}
