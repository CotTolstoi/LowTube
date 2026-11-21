package com.lowtube.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lowtube.android.AppLocale
import kotlinx.coroutines.CancellationException

/** Refreshes a bounded batch from local subscriptions without account credentials or cookies. */
class SubscriptionFeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preset = inputData.getInt(SubscriptionFeedRefreshScheduler.INPUT_PRESET, DEFAULT_PRESET)
            .coerceIn(0, 2)
        val library = LocalLibraryRepository(applicationContext)
        return try {
            if (library.localChannels().isEmpty()) return Result.success()
            val result = LocalSubscriptionFeedRepository(library, InnerTubeSearchRepository(applicationContext))
                .refreshBatch(AppLocale.language(applicationContext), preset)
            if (result.refreshedChannels > 0) SubscriptionFeedRefreshScheduler.notifyFeedUpdated()
            if (result.refreshedChannels == 0 && result.failedChannels.isNotEmpty()) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        } finally {
            library.close()
        }
    }

    companion object {
        private const val DEFAULT_PRESET = 1
    }
}
