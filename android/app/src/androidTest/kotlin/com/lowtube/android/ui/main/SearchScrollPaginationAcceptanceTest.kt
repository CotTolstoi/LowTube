package com.lowtube.android.ui.main

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Search screen pagination check. It scrolls public results and never starts playback. */
@RunWith(AndroidJUnit4::class)
class SearchScrollPaginationAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scrollingSearchResultsRequestsTheNextPage() {
        assumeTrue(
            "Live search scroll acceptance is opt-in; pass -e runSearchScrollAcceptance true",
            InstrumentationRegistry.getArguments().getString("runSearchScrollAcceptance") == "true",
        )

        val activity = composeTestRule.activity
        val searchTab = hasText(activity.getString(R.string.search_tab)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeTestRule.onNode(searchTab).performClick()

        val viewModel = ViewModelProvider(activity)[SearchViewModel::class.java]
        activity.runOnUiThread { viewModel.search(QUERY, language = "en", pageSize = 25) }
        composeTestRule.waitUntil(60_000) {
            val state = viewModel.state.value
            !state.loading && (state.items.isNotEmpty() || state.error != null)
        }
        val firstPage = viewModel.state.value
        assertTrue("Search failed before the first page: ${firstPage.error}", firstPage.items.isNotEmpty())
        assertTrue("Search returned no continuation", !firstPage.continuation.isNullOrBlank())
        val firstIds = firstPage.items.map { it.kind + ":" + it.id }.toSet()
        val firstContinuation = firstPage.continuation
        System.out.println("searchScrollInitial; items=${firstPage.items.size}; limit=${firstPage.displayLimit}; home=${firstPage.isHome}")

        val resultsList = composeTestRule.onNodeWithTag(SEARCH_RESULTS_LIST_TEST_TAG)
        repeat(30) {
            resultsList.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }
        val afterGestures = viewModel.state.value
        System.out.println(
            "searchScrollGestures; items=${afterGestures.items.size}; limit=${afterGestures.displayLimit}; " +
                "loadingMore=${afterGestures.loadingMore}; continuationAdvanced=${afterGestures.continuation != firstContinuation}",
        )
        val appended = runCatching {
            composeTestRule.waitUntil(10_000) {
                val state = viewModel.state.value
                state.items.any { (it.kind + ":" + it.id) !in firstIds } || state.error != null
            }
        }.isSuccess
        val afterScroll = viewModel.state.value
        assertTrue(
            "Scrolling did not append results: observed=$appended, before=${firstPage.items.size}, after=${afterScroll.items.size}, " +
                "initialLimit=${firstPage.displayLimit}, limit=${afterScroll.displayLimit}, loadingMore=${afterScroll.loadingMore}, " +
                "hasContinuation=${!afterScroll.continuation.isNullOrBlank()}, error=${afterScroll.error}",
            afterScroll.items.any { (it.kind + ":" + it.id) !in firstIds },
        )
    }

    private companion object {
        const val QUERY = "NASA space science"
    }
}
