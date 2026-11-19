package com.lowtube.android.data

/** Android equivalent of the PC connection preset for a user-requested feed refresh. */
internal data class SubscriptionFeedRefreshPolicy(
    val channelsPerBatch: Int,
    val maxConcurrentRequests: Int,
    val refreshIntervalMinutes: Long,
) {
    companion object {
        fun fromPreset(preset: Int): SubscriptionFeedRefreshPolicy = when (preset) {
            0 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 2, maxConcurrentRequests = 1, refreshIntervalMinutes = 30)
            1 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 4, maxConcurrentRequests = 2, refreshIntervalMinutes = 15)
            // Android's supported periodic-work floor is 15 minutes, even for the fastest profile.
            2 -> SubscriptionFeedRefreshPolicy(channelsPerBatch = 6, maxConcurrentRequests = 3, refreshIntervalMinutes = 15)
            else -> throw IllegalArgumentException("Unsupported subscription feed refresh preset.")
        }
    }
}
