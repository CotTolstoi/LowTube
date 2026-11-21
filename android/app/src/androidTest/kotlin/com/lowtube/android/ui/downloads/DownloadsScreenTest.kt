package com.lowtube.android.ui.downloads

import androidx.activity.ComponentActivity
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lowtube.android.R
import com.lowtube.android.data.DownloadJobStore
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.model.DownloadStatus
import com.lowtube.android.ui.theme.LowTubeTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DownloadsScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun retryReloadsQueueAfterDatabaseReadFailure() {
        val context = composeTestRule.activity
        val databaseName = "downloads-read-error-${System.nanoTime()}.db"
        val seedStore = DownloadJobStore(context, databaseName)
        val expectedVersion = try { seedStore.writableDatabase.version } finally { seedStore.close() }
        fun changeVersion(version: Int) {
            val db = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
            try { db.version = version } finally { db.close() }
        }
        changeVersion(expectedVersion + 1)
        val repository = DownloadRepository(context, databaseName)
        try {
            composeTestRule.setContent {
                LowTubeTheme {
                    DownloadsScreen(repository, onBack = {}, onRetry = { _, done -> done() }, onOpen = {})
                }
            }
            val error = context.getString(R.string.downloads_read_failed)
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty()
            }
            changeVersion(expectedVersion)
            composeTestRule.onNodeWithText(context.getString(R.string.retry)).performClick()
            val empty = context.getString(R.string.downloads_empty)
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(empty).assertIsDisplayed()
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pausedQueueEntryShowsProgressCanRetryAndCanBeRemoved() {
        val context = composeTestRule.activity
        val databaseName = "downloads-screen-${System.currentTimeMillis()}.db"
        val partial = File(context.cacheDir, "$databaseName.part")
        val repository = DownloadRepository(context, databaseName)
        val seedStore = DownloadJobStore(context, databaseName)
        val videoJson = JSONObject()
            .put("id", "downloads-screen-fixture")
            .put("title", "Paused screen fixture")
            .put("url", "https://www.youtube.com/watch?v=downloads-screen-fixture")
            .toString()
        var retryId = 0L
        var jobId = 0L

        try {
            jobId = seedStore.insert(
                videoJson,
                "fixture-format",
                false,
                "paused-screen-fixture.mp4",
                "video/mp4",
                "test",
                partial.absolutePath,
            )
            seedStore.update(jobId, DownloadStatus.PAUSED, downloaded = 32L, total = 64L)
            seedStore.close()

            composeTestRule.setContent {
                LowTubeTheme {
                    DownloadsScreen(
                        repository = repository,
                        onBack = {},
                        onRetry = { id, finished ->
                            retryId = id
                            finished()
                        },
                        onOpen = {},
                    )
                }
            }

            composeTestRule.onNodeWithText(context.getString(R.string.downloads)).assertIsDisplayed()
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithText("Paused screen fixture").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText("Paused screen fixture").assertIsDisplayed()
            composeTestRule.onNodeWithText("50% • 32 B / 64 B").assertIsDisplayed()
            val density = context.resources.displayMetrics.density
            val gridBounds = composeTestRule.onNodeWithTag(DOWNLOADS_GRID_TEST_TAG).fetchSemanticsNode().boundsInRoot
            val cardBounds = composeTestRule.onNodeWithTag("download-entry-$jobId").fetchSemanticsNode().boundsInRoot
            if (gridBounds.width / density >= 760f) {
                assertTrue("Wide downloads should use a card width smaller than the full window", cardBounds.width < gridBounds.width * 0.75f)
            } else {
                assertTrue("Compact downloads should keep a full-width card", cardBounds.width >= gridBounds.width * 0.75f)
            }
            composeTestRule.onNodeWithText(context.getString(R.string.retry)).performClick()
            assertEquals("Retry should target the visible queue entry", jobId, retryId)

            composeTestRule.onNodeWithText(context.getString(R.string.remove)).performClick()
            composeTestRule.waitUntil(5_000L) {
                composeTestRule.onAllNodesWithText("Paused screen fixture").fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.onNodeWithText(context.getString(R.string.downloads_empty)).assertIsDisplayed()
            assertTrue("Remove should delete the incomplete queue job", repository.records().isEmpty())
        } finally {
            seedStore.close()
            repository.close()
            partial.delete()
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("lowtube_downloads_$databaseName", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun emptyQueueShowsEmptyStateAndBackAction() {
        val context = composeTestRule.activity
        val databaseName = "downloads-empty-screen-${System.currentTimeMillis()}.db"
        val repository = DownloadRepository(context, databaseName)
        var backRequested = false
        try {
            composeTestRule.setContent {
                LowTubeTheme {
                    DownloadsScreen(
                        repository = repository,
                        onBack = { backRequested = true },
                        onRetry = { _, finished -> finished() },
                        onOpen = {},
                    )
                }
            }

            val empty = context.getString(R.string.downloads_empty)
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(empty).assertIsDisplayed()
            composeTestRule.onNodeWithText(context.getString(R.string.back)).performClick()
            assertTrue("Back action should be forwarded from Downloads", backRequested)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("lowtube_downloads_$databaseName", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
