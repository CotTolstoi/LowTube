package com.lowtube.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPagePolicyTest {
    @Test
    fun revealsBufferedResultsInPageSizedChunks() {
        assertEquals(20, FeedPagePolicy.nextDisplayLimit(currentLimit = 10, pageSize = 10, availableCount = 24))
        assertEquals(24, FeedPagePolicy.nextDisplayLimit(currentLimit = 20, pageSize = 10, availableCount = 24))
    }

    @Test
    fun onlyRequestsAnotherNetworkPageAfterBufferedItemsAreVisible() {
        assertTrue(FeedPagePolicy.hasBufferedResults(availableCount = 21, displayLimit = 20))
        assertFalse(FeedPagePolicy.hasBufferedResults(availableCount = 20, displayLimit = 20))
    }
}
