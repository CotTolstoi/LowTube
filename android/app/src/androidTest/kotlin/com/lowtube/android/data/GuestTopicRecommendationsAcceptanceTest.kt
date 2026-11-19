package com.lowtube.android.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in guest recommendation metadata check; never resolves or plays media. */
@RunWith(AndroidJUnit4::class)
class GuestTopicRecommendationsAcceptanceTest {
    @Test
    fun loadsGuestTopicRecommendationsWithoutPlayback() {
        runBlocking {
            assumeTrue(
                "Live recommendation acceptance is opt-in; pass -e runGuestRecommendationsAcceptance true",
                InstrumentationRegistry.getArguments().getString("runGuestRecommendationsAcceptance") == "true",
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = InnerTubeSearchRepository(context)
            val page = repository.recommendations(
                language = "en",
                topic = "science and technology",
                discoveryLanguage = "any",
            )

            assertTrue("Guest topic feed returned no displayable recommendations", page.items.any { it.kind in DISPLAYABLE_KINDS })
            assertTrue("Guest continuation was not source-qualified", page.continuation == null || page.continuation.startsWith(TOPIC_CONTINUATION_PREFIX))
            val continuation = page.continuation
            if (continuation != null) {
                val secondPage = repository.recommendations(
                    language = "en",
                    topic = "science and technology",
                    discoveryLanguage = "any",
                    continuation = continuation,
                )
                assertTrue("Guest continuation returned no displayable recommendations", secondPage.items.any { it.kind in DISPLAYABLE_KINDS })
                assertTrue("Guest continuation returned the same leading item", secondPage.items.first().id !in page.items.map { it.id })
                assertTrue(
                    "Second-page continuation lost its source prefix",
                    secondPage.continuation == null || secondPage.continuation.startsWith(TOPIC_CONTINUATION_PREFIX),
                )
                Log.i(TAG, "firstPage=${page.items.size} secondPage=${secondPage.items.size} continuation=${secondPage.continuation != null} source=guest-topic")
            } else {
                Log.i(TAG, "firstPage=${page.items.size} secondPage=0 continuation=false source=terminal")
            }
        }
    }

    private companion object {
        const val TAG = "LowTubeGuestRecommendations"
        const val TOPIC_CONTINUATION_PREFIX = "lowtube:topic:"
        val DISPLAYABLE_KINDS = setOf("video", "shorts", "live")
    }
}
