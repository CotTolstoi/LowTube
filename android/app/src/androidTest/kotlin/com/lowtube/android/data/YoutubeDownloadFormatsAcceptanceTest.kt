package com.lowtube.android.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.VideoItem
import com.lowtube.android.downloads.OkHttpDownloadTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in live resolver + bounded shared-transport check; never plays media or downloads more than 64 KiB. */
@RunWith(AndroidJUnit4::class)
class YoutubeDownloadFormatsAcceptanceTest {
    @Test
    fun listsUsableDirectFormatsWithoutStartingMedia() {
        runBlocking {
            assumeTrue(
                "Live download-format acceptance is opt-in; pass -e runDownloadFormatsAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadFormatsAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val video = VideoItem(
                id = "aqz-KE-bpKQ",
                title = "Big Buck Bunny",
                duration = "9:56",
                url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            )
            val repository = InnerTubeSearchRepository(context)
            val videoOptions = repository.resolveDownloadOptions(video, DownloadStreamPreferences(), audioOnly = false)
            val audioOptions = repository.resolveDownloadOptions(video, DownloadStreamPreferences(), audioOnly = true)

            assertFalse("Expected at least one directly downloadable video format", videoOptions.isEmpty())
            assertTrue(videoOptions.all { it.stream.isMuxed && !it.audioOnly && it.stream.videoHeight <= 720 })
            assertTrue(videoOptions.all { option ->
                val uri = Uri.parse(option.stream.uri)
                val host = uri.host?.lowercase().orEmpty()
                uri.scheme == "https" && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
            })
            val selected = videoOptions.first().stream
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            val tempFile = File(context.cacheDir, "lowtube-bounded-range-${System.currentTimeMillis()}.part")
            try {
                val progress = OkHttpDownloadTransport(client).download(
                    selected, video, tempFile, rateLimitKiB = 0, shouldCancel = { false },
                    maxBytesThisAttempt = MAX_PROBE_BYTES.toLong(), requirePartialResponse = true,
                )
                assertEquals(MAX_PROBE_BYTES.toLong(), progress.downloaded)
                assertEquals(MAX_PROBE_BYTES.toLong(), tempFile.length())
                val resumed = OkHttpDownloadTransport(client).download(
                    selected, video, tempFile, rateLimitKiB = 0, shouldCancel = { false },
                    maxBytesThisAttempt = MAX_PROBE_BYTES.toLong(), requirePartialResponse = true,
                )
                Log.i("LowTubeDownloadFormats", "videoOptions=${videoOptions.size}, audioOptions=${audioOptions.size}, topVideoItag=${selected.itag}, resumedBytes=${resumed.downloaded}, totalBytes=${resumed.total}")
                assertEquals((MAX_PROBE_BYTES * 2).toLong(), resumed.downloaded)
                assertEquals((MAX_PROBE_BYTES * 2).toLong(), tempFile.length())
                assertTrue("Transport wrote beyond two bounded ranges", tempFile.length() <= MAX_PROBE_BYTES * 2L)
                assertTrue("The bounded transfer must leave a partial file for retry", resumed.total > resumed.downloaded)
            } finally {
                tempFile.delete()
            }
        }
    }

    private companion object {
        const val MAX_PROBE_BYTES = 65_536
    }
}
