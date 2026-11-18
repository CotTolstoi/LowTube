package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionFeedRefreshPolicyTest {
    @Test
    fun mapsPcConnectionPresetsToBoundedAndroidBatches() {
        assertEquals(SubscriptionFeedRefreshPolicy(2, 1), SubscriptionFeedRefreshPolicy.fromPreset(0))
        assertEquals(SubscriptionFeedRefreshPolicy(4, 2), SubscriptionFeedRefreshPolicy.fromPreset(1))
        assertEquals(SubscriptionFeedRefreshPolicy(6, 3), SubscriptionFeedRefreshPolicy.fromPreset(2))
    }

    @Test
    fun rejectsUnsupportedPreset() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionFeedRefreshPolicy.fromPreset(-1) }
        assertThrows(IllegalArgumentException::class.java) { SubscriptionFeedRefreshPolicy.fromPreset(3) }
    }
}
