package com.lowtube.android.data

/** Android equivalent of the PC connection preset for a user-requested feed refresh. */
internal data class SubscriptionFeedRefreshPolicy(
    val channelsPerBatch: Int,
    val maxConcurrentRequests: Int,
) {
    companion object {
        fun fromPreset(preset: Int): SubscriptionFeedRefreshPolicy = when (preset) {
            0 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 2, maxConcurrentRequests = 1)
            1 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 4, maxConcurrentRequests = 2)
            2 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 6, maxConcurrentRequests = 3)
            else -> throw IllegalArgumentException("Unsupported subscription feed refresh preset.")
        }
    }
}
