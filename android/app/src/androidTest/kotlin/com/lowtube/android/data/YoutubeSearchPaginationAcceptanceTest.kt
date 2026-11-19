package com.lowtube.android.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, two-page public search check. It fetches metadata only and never starts playback. */
@RunWith(AndroidJUnit4::class)
class YoutubeSearchPaginationAcceptanceTest {
    @Test
    fun publicSearchContinuationReturnsDistinctSecondPage() = runBlocking {
        assumeTrue(
            "Live search pagination acceptance is opt-in; pass -e runSearchPaginationAcceptance true",
            InstrumentationRegistry.getArguments().getString("runSearchPaginationAcceptance") == "true",
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = InnerTubeSearchRepository(context)
        val first = repository.search(QUERY, language = "en")
        assertTrue("The public search returned no first-page items", first.items.isNotEmpty())
        val firstContinuation = first.continuation
        assertFalse("The public search has no continuation to verify", firstContinuation.isNullOrBlank())

        val second = repository.search(QUERY, continuation = firstContinuation, language = "en")
        assertTrue("The public search returned no second-page items", second.items.isNotEmpty())
        assertNotEquals("The continuation did not advance", firstContinuation, second.continuation)
        val firstIds = first.items.map { it.kind + ":" + it.id }.toSet()
        val secondIds = second.items.map { it.kind + ":" + it.id }
        assertFalse("The second page contained no new results", secondIds.all(firstIds::contains))
        assertTrue("Second-page results were not unique", secondIds.size == secondIds.toSet().size)
        System.out.println(
            "youtubeSearchPagination; firstItems=${first.items.size}; secondItems=${second.items.size}; " +
                "secondPageUnique=${secondIds.size}; hasFurtherContinuation=${!second.continuation.isNullOrBlank()}",
        )
    }

    private companion object {
        const val QUERY = "NASA space science"
    }
}
