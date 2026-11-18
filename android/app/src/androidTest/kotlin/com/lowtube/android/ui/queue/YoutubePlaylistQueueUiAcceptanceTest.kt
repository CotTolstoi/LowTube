package com.lowtube.android.ui.queue

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.R
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.launch
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in bounded live UI acceptance. It expands two metadata pages and never creates Media3. */
class YoutubePlaylistQueueUiAcceptanceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun queueScreenLoadsTwoLivePlaylistPagesInOrderWithoutPlayback() {
        assumeTrue(
            "Live playlist UI acceptance is opt-in; pass -e runPlaylistQueueUiAcceptance true",
            InstrumentationRegistry.getArguments().getString("runPlaylistQueueUiAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "youtube-playlist-queue-ui-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val catalogue = InnerTubeSearchRepository(context)
        val fetchedVideoIds = Collections.synchronizedList(mutableListOf<String>())
        val fetchedContinuations = Collections.synchronizedList(mutableListOf<String?>())
        library.enqueue(VideoItem(id = PLAYLIST_ID, title = PLAYLIST_TITLE, kind = "playlist", url = PLAYLIST_URL))
        try {
            composeTestRule.setContent {
                var revision by remember { mutableIntStateOf(0) }
                var expandingRowId by remember { mutableStateOf<Long?>(null) }
                val scope = rememberCoroutineScope()
                MaterialTheme {
                    QueueScreen(
                        repository = library,
                        refreshRevision = revision,
                        onBack = {},
                        onPlay = { error("This acceptance must not start playback") },
                        onExpandPlaylist = { entry ->
                            expandingRowId = entry.rowId
                            scope.launch {
                                try {
                                    val page = catalogue.search(
                                        entry.video.url,
                                        continuation = entry.continuation.ifBlank { null },
                                        language = "en",
                                    )
                                    val videos = page.items.filter { it.kind == "video" }
                                    System.out.println(
                                        "youtubePlaylistQueueUiPage; index=${library.queue().count { it.video.kind != "playlist" } / PAGE_SIZE}; " +
                                            "videos=${videos.size}; hasContinuation=${!page.continuation.isNullOrBlank()}; " +
                                            "continuationChars=${page.continuation?.length ?: 0}",
                                    )
                                    synchronized(fetchedVideoIds) { fetchedVideoIds.addAll(videos.map { it.id }) }
                                    synchronized(fetchedContinuations) { fetchedContinuations.add(page.continuation) }
                                    library.expandQueuedPlaylist(entry.rowId, videos, page.continuation)
                                    revision++
                                } finally {
                                    expandingRowId = null
                                }
                            }
                        },
                        expandingPlaylistRowId = expandingRowId,
                        autoNext = true,
                        onAutoNextChanged = {},
                    )
                }
            }

            val expectedIds = mutableListOf<String>()
            repeat(PAGE_COUNT) { pageIndex ->
                val marker = library.queue().single { it.video.kind == "playlist" }
                val previousVideoCount = library.queue().count { it.video.kind == "video" }
                composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(PLAYLIST_TITLE))
                composeTestRule.onNodeWithText(context.getString(R.string.load_playlist_page)).performClick()
                composeTestRule.waitUntil(UI_PAGE_TIMEOUT_MS) {
                    val queue = library.queue()
                    queue.count { it.video.kind == "video" } > previousVideoCount ||
                        queue.none { it.video.kind == "playlist" }
                }

                val queue = library.queue()
                val sourceIds = synchronized(fetchedVideoIds) { fetchedVideoIds.drop(previousVideoCount).toList() }
                val sourceContinuation = synchronized(fetchedContinuations) { fetchedContinuations[pageIndex] }
                expectedIds += sourceIds
                assertTrue("Live page $pageIndex was empty", expectedIds.size > previousVideoCount)
                assertEquals("Queue page $pageIndex differs from the fetched page", PAGE_SIZE, sourceIds.size)
                if (pageIndex < PAGE_COUNT - 1) {
                    assertTrue("Continuation marker missing after page $pageIndex", queue.any { it.video.kind == "playlist" })
                    assertTrue("Continuation token did not advance after page $pageIndex", queue.single { it.video.kind == "playlist" }.continuation.isNotBlank())
                } else {
                    assertEquals("The bounded acceptance should leave a continuation marker", marker.video.id, queue.single { it.video.kind == "playlist" }.video.id)
                }
                assertEquals(sourceContinuation, queue.single { it.video.kind == "playlist" }.continuation)
                assertEquals(expectedIds, queue.filter { it.video.kind == "video" }.map { it.video.id })
            }

            assertEquals(PAGE_COUNT * PAGE_SIZE, expectedIds.size)
        } finally {
            library.close()
            context.deleteDatabase(databaseName)
        }
    }

    private companion object {
        const val PLAYLIST_ID = "UU_aP7p621ATY_yAa8jMqUVA"
        const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=$PLAYLIST_ID"
        const val PLAYLIST_TITLE = "NASA Video uploads"
        const val PAGE_COUNT = 2
        const val PAGE_SIZE = 100
        const val UI_PAGE_TIMEOUT_MS = 90_000L
    }
}
