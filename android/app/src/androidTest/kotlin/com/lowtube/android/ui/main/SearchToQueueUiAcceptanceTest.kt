package com.lowtube.android.ui.main

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live UI route test. It adds/removes one known video in the emulator's local queue and never plays it. */
@RunWith(AndroidJUnit4::class)
class SearchToQueueUiAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchResultCanBeAddedToAndFoundInQueueWithoutPlayback() {
        assumeTrue(
            "Live Search→Queue acceptance is opt-in; pass -e runSearchToQueueAcceptance true",
            InstrumentationRegistry.getArguments().getString("runSearchToQueueAcceptance") == "true",
        )

        val activity = composeTestRule.activity
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val library = LocalLibraryRepository(context)
        var queueScreenOpened = false
        try {
            assertFalse("The acceptance fixture is already in the local queue", library.queue().any { it.video.id == VIDEO_ID })
            activity.runOnUiThread {
                activity.acceptExternalYoutubeIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_URL)).setClass(context, MainActivity::class.java),
                )
            }

            composeTestRule.waitUntil(60_000) {
                composeTestRule.onAllNodesWithText(VIDEO_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            val moreActions = activity.getString(R.string.more_actions)
            assertEquals(1, composeTestRule.onAllNodesWithText(VIDEO_TITLE, substring = true).fetchSemanticsNodes().size)
            composeTestRule.onNodeWithContentDescription(moreActions).performClick()
            composeTestRule.onNodeWithText(activity.getString(R.string.add_to_queue)).performClick()

            composeTestRule.waitUntil(5_000) {
                library.queue().any { it.video.id == VIDEO_ID && it.video.title.contains(VIDEO_TITLE, ignoreCase = true) }
            }
            composeTestRule.onNodeWithText(activity.getString(R.string.more_menu), substring = true).performClick()
            composeTestRule.onNodeWithText(activity.getString(R.string.watch_queue)).performClick()
            queueScreenOpened = true
            composeTestRule.onNodeWithText(VIDEO_TITLE, substring = true).assertExists()
            assertTrue("Search did not add the expected playable item", library.queue().any { it.video.id == VIDEO_ID })
        } finally {
            library.queue().filter { it.video.id == VIDEO_ID }.forEach { library.removeQueueEntry(it.rowId) }
            library.close()
            if (queueScreenOpened) {
                activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                composeTestRule.waitForIdle()
            }
        }
    }

    private companion object {
        const val VIDEO_ID = "aqz-KE-bpKQ"
        const val VIDEO_TITLE = "Big Buck Bunny"
        const val VIDEO_URL = "https://www.youtube.com/watch?v=$VIDEO_ID"
    }
}
