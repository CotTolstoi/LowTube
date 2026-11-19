package com.lowtube.android.ui.main

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live For you feed test: scrolls public recommendation cards and never plays media. */
@RunWith(AndroidJUnit4::class)
class ForYouScrollPaginationAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scrollingForYouRecommendationsRequestsAnotherTopicPage() {
        assumeTrue(
            "Live For you pagination is opt-in; pass -e runForYouScrollAcceptance true",
            InstrumentationRegistry.getArguments().getString("runForYouScrollAcceptance") == "true",
        )

        val initialActivity = composeTestRule.activity
        val settings = initialActivity.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE)
        val hadFirstRunValue = settings.contains("first_run_complete")
        val previousFirstRunValue = settings.getBoolean("first_run_complete", false)
        try {
            if (!previousFirstRunValue) {
                check(settings.edit().putBoolean("first_run_complete", true).commit())
                composeTestRule.activityRule.scenario.recreate()
                composeTestRule.waitForIdle()
            }

            val activity = composeTestRule.activity
            val recommendationsTab = hasText(activity.getString(R.string.recommendations_tab)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
            composeTestRule.waitUntil(10_000) {
                composeTestRule.onAllNodesWithText(activity.getString(R.string.recommendations_tab)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(recommendationsTab).performClick()

            val viewModel = ViewModelProvider(activity)[SearchViewModel::class.java]
            composeTestRule.waitUntil(60_000) {
                val state = viewModel.state.value
                !state.loading && state.isHome && (state.items.isNotEmpty() || state.error != null)
                }
            val firstPage = viewModel.state.value
            assertTrue("For you failed before its first page: ${firstPage.error}", firstPage.items.isNotEmpty())
            assertTrue("For you returned no topic continuation", !firstPage.continuation.isNullOrBlank())
            val firstIds = firstPage.items.map { it.kind + ":" + it.id }.toSet()
            val firstContinuation = firstPage.continuation
            System.out.println(
                "forYouScrollInitial; items=${firstPage.items.size}; limit=${firstPage.displayLimit}; " +
                    "hasContinuation=${!firstContinuation.isNullOrBlank()}",
            )

            val resultsGrid = composeTestRule.onNodeWithTag(SEARCH_RESULTS_LIST_TEST_TAG)
            fun scrollUntilNewItems(existingIds: Set<String>): Boolean {
                var appended = false
                repeat(MAX_SCROLL_GESTURES) {
                    if (!appended) {
                        resultsGrid.performTouchInput { swipeUp() }
                        composeTestRule.waitForIdle()
                        appended = viewModel.state.value.items.any { (it.kind + ":" + it.id) !in existingIds }
                    }
                }
                if (!appended) {
                    appended = runCatching {
                        composeTestRule.waitUntil(20_000) {
                            viewModel.state.value.items.any { (it.kind + ":" + it.id) !in existingIds } ||
                                (!viewModel.state.value.loadingMore && viewModel.state.value.error != null)
                        }
                    }.isSuccess && viewModel.state.value.items.any { (it.kind + ":" + it.id) !in existingIds }
                }
                return appended
            }

            assertTrue("Scrolling did not append the second topic page", scrollUntilNewItems(firstIds))
            val afterSecondPage = viewModel.state.value
            assertTrue(
                "For you scroll did not append a new recommendation: first=${firstPage.items.size}, " +
                    "afterSecondPage=${afterSecondPage.items.size}, initialLimit=${firstPage.displayLimit}, " +
                    "finalLimit=${afterSecondPage.displayLimit}, loadingMore=${afterSecondPage.loadingMore}, " +
                    "continuationAdvanced=${afterSecondPage.continuation != firstContinuation}, error=${afterSecondPage.error}",
                afterSecondPage.items.any { (it.kind + ":" + it.id) !in firstIds },
            )
            assertTrue("The feed left guest-topic pagination", afterSecondPage.isHome)
            assertTrue("The topic continuation did not advance", afterSecondPage.continuation != firstContinuation)
            val secondContinuation = afterSecondPage.continuation
                ?: throw AssertionError("The guest topic feed ended before a third page could be checked")
            val secondPageIds = afterSecondPage.items.map { it.kind + ":" + it.id }.toSet()
            assertTrue("Scrolling did not append the third topic page", scrollUntilNewItems(secondPageIds))

            val finalState = viewModel.state.value
            assertTrue("The final topic continuation did not advance", finalState.continuation != secondContinuation)
            System.out.println(
                "forYouScrollFinal; items=${finalState.items.size}; limit=${finalState.displayLimit}; " +
                    "twoPagesAppended=${finalState.items.any { (it.kind + ":" + it.id) !in secondPageIds }}",
            )
        } finally {
            settings.edit().apply {
                if (hadFirstRunValue) putBoolean("first_run_complete", previousFirstRunValue)
                else remove("first_run_complete")
            }.commit()
        }
    }

    private companion object {
        const val MAX_SCROLL_GESTURES = 80
    }
}
