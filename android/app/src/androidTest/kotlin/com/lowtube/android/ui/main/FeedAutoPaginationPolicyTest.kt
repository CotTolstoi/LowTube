package com.lowtube.android.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedAutoPaginationPolicyTest {
    @Test
    fun prefetchesOnlyNearTheEndOfANonEmptyPage() {
        assertFalse(FeedAutoPaginationPolicy.shouldPrefetch(-1, 20))
        assertFalse(FeedAutoPaginationPolicy.shouldPrefetch(12, 20))
        assertTrue(FeedAutoPaginationPolicy.shouldPrefetch(16, 20))
        assertTrue(FeedAutoPaginationPolicy.shouldPrefetch(19, 20))
        assertFalse(FeedAutoPaginationPolicy.shouldPrefetch(0, 0))
    }
}
