package com.lowtube.android.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Keeps one OS-managed periodic refresh, only while local channel subscriptions exist. */
object SubscriptionFeedRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "lowtube-local-subscription-feed-refresh"
    const val INPUT_PRESET = "feed_refresh_preset"

    suspend fun synchronize(context: Context, preset: Int, hasLocalChannels: Boolean) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!hasLocalChannels) {
            manager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get()
            return@withContext
        }

        val safePreset = preset.coerceIn(0, 2)
        val activeWork = manager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .firstOrNull { !it.state.isFinished }
        val presetTag = presetTag(safePreset)
        if (activeWork?.tags?.contains(presetTag) == true) return@withContext

        val policy = SubscriptionFeedRefreshPolicy.fromPreset(safePreset)
        val request = PeriodicWorkRequestBuilder<SubscriptionFeedRefreshWorker>(
            policy.refreshIntervalMinutes,
            TimeUnit.MINUTES,
        )
            .addTag(presetTag)
            .setInputData(androidx.work.workDataOf(INPUT_PRESET to safePreset))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        val existingPolicy = if (activeWork == null) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.REPLACE
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, existingPolicy, request).result.get()
    }

    private fun presetTag(preset: Int): String = "lowtube-feed-preset-$preset"
}
