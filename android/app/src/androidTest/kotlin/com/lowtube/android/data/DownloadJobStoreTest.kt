package com.lowtube.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadJobStoreTest {
    @Test
    fun aggregatesOnlyPersistedDownloadStatusesForDiagnostics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "download-queue-status-${System.currentTimeMillis()}.db"
        val store = DownloadJobStore(context, database)
        try {
            val pendingId = store.insert("{\"title\":\"private title\",\"url\":\"https://youtube.com/watch?v=AbCdEfGhI12\"}", "a", false, "secret.mp4", "video/mp4", "agent", "/private/secret.part")
            val failedId = store.insert("{\"title\":\"another private title\"}", "b", false, "another-secret.mp4", "video/mp4", "agent", "/private/another.part")
            store.update(pendingId, DownloadStatus.RUNNING, reason = "private failure details")
            store.update(failedId, DownloadStatus.FAILED, reason = "private failure details")

            val counts = store.statusCounts()
            assertEquals(mapOf(DownloadStatus.RUNNING to 1L, DownloadStatus.FAILED to 1L), counts)
        } finally {
            store.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun persistsQueueProgressAndRequeuesInterruptedJobsWithoutSignedUrls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "download-queue-test-${System.currentTimeMillis()}.db"
        val store = DownloadJobStore(context, database)
        try {
            val id = store.insert("""{"id":"fixture","title":"Fixture","url":"https://www.youtube.com/watch?v=fixture"}""", "18|video/mp4|false", false, "fixture.mp4", "video/mp4", "test-agent", "/tmp/fixture.part")
            assertTrue("Queue IDs should not collide with Android DownloadManager IDs", id >= 1_000_000_000_000L)
            assertEquals(DownloadStatus.PENDING, store.get(id)?.status)
            store.update(id, DownloadStatus.RUNNING, downloaded = 65_536, total = 1_000_000)
            assertEquals(65_536L, store.get(id)?.downloadedBytes)
            assertNotNull(store.pending())
            store.requeueInterrupted()
            val resumed = store.get(id)
            assertEquals(DownloadStatus.PENDING, resumed?.status)
            assertEquals(65_536L, resumed?.downloadedBytes)
            assertTrue("Persisted job metadata must not contain a signed GoogleVideo URL", store.all().none { it.videoJson.contains("googlevideo") })
            store.update(id, DownloadStatus.FAILED, "fixture error")
            assertNull(store.pending())
            assertTrue(store.requeueRetryable(id))
            assertEquals(DownloadStatus.PENDING, store.get(id)?.status)
            assertEquals(65_536L, store.get(id)?.downloadedBytes)
            assertEquals(1_000_000L, store.get(id)?.totalBytes)
            assertFalse(store.requeueRetryable(id))
            store.update(id, DownloadStatus.SUCCESSFUL)
            assertFalse(store.requeueRetryable(id))
            assertEquals(DownloadStatus.SUCCESSFUL, store.get(id)?.status)
        } finally {
            store.close()
            context.deleteDatabase(database)
        }
    }
}
