package com.lowtube.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionFeedRefreshSchedulerTest {
    @Test
    fun schedulesConnectedPeriodicWorkAndReplacesItWhenPresetChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        val workName = "lowtube-local-subscription-feed-refresh"
        manager.cancelUniqueWork(workName).result.get()
        try {
            SubscriptionFeedRefreshScheduler.synchronize(context, preset = 1, hasLocalChannels = true)
            val initial = manager.getWorkInfosForUniqueWork(workName).get().single()
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, initial.state)
            assertTrue(initial.tags.contains("lowtube-feed-preset-1"))
            assertEquals(NetworkType.CONNECTED, initial.constraints.requiredNetworkType)

            SubscriptionFeedRefreshScheduler.synchronize(context, preset = 1, hasLocalChannels = true)
            val unchanged = manager.getWorkInfosForUniqueWork(workName).get().single()
            assertEquals(initial.id, unchanged.id)

            SubscriptionFeedRefreshScheduler.synchronize(context, preset = 0, hasLocalChannels = true)
            val replaced = manager.getWorkInfosForUniqueWork(workName).get().single { !it.state.isFinished }
            assertFalse(replaced.id == initial.id)
            assertTrue(replaced.tags.contains("lowtube-feed-preset-0"))
        } finally {
            manager.cancelUniqueWork(workName).result.get()
        }
    }
}
