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

/** Opt-in YouTube Home metadata check; reads one browse page and never resolves or plays media. */
@RunWith(AndroidJUnit4::class)
class YoutubeHomeRecommendationsAcceptanceTest {
    @Test
    fun loadsPublicYouTubeHomeRecommendationsWithoutPlayback() {
        runBlocking {
            assumeTrue(
                "Live Home-feed acceptance is opt-in; pass -e runHomeFeedAcceptance true",
                InstrumentationRegistry.getArguments().getString("runHomeFeedAcceptance") == "true",
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val page = InnerTubeSearchRepository(context).recommendations(
                language = "en",
                topic = "science and technology",
                discoveryLanguage = "any",
            )

            assertTrue("YouTube Home returned no displayable recommendations", page.items.any { it.kind in DISPLAYABLE_KINDS })
            assertTrue(
                "Home continuation was not source-qualified",
                page.continuation == null || page.continuation.startsWith(HOME_CONTINUATION_PREFIX) ||
                    page.continuation.startsWith(TOPIC_CONTINUATION_PREFIX),
            )
            Log.i(TAG, "homeItemCount=${page.items.size} continuation=${page.continuation != null} source=${page.continuation?.substringBefore(':') ?: "terminal"}")
        }
    }

    private companion object {
        const val TAG = "LowTubeHomeFeed"
        const val HOME_CONTINUATION_PREFIX = "lowtube:home:"
        const val TOPIC_CONTINUATION_PREFIX = "lowtube:topic:"
        val DISPLAYABLE_KINDS = setOf("video", "shorts", "live")
    }
}
