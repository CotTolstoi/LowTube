package com.lowtube.android.data

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in live resolver smoke check; reads at most 64 KiB per stream and never starts Media3 playback. */
@RunWith(AndroidJUnit4::class)
class YoutubeResolverRangeAcceptanceTest {
    @Test
    fun resolvesAndReadsBoundedVideoAndAudioRanges() = runBlocking {
        assumeTrue(
            "Pass -e runResolverRangeAcceptance true to run this bounded live network check",
            InstrumentationRegistry.getArguments().getString("runResolverRangeAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = InnerTubeSearchRepository(context)
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val cpuStarted = Process.getElapsedCpuTime()
        val wallStarted = SystemClock.elapsedRealtime()
        val pssStarted = Debug.getPss()
        val rssStarted = readRssKiB()

        val videoOptions = repository.resolveDownloadOptions(video, DownloadStreamPreferences(), audioOnly = false)
        val videoOption = videoOptions.minByOrNull { it.stream.videoHeight }
            ?: error("The Android/WEB clients returned no direct video download option.")
        val videoRange = requestRange(videoOption.stream.uri, videoOption.stream.userAgent)
        assertTrue("The video URL did not resolve to a googlevideo response", videoRange.contentType.startsWith("video/"))

        val audioOptions = repository.resolveDownloadOptions(video, DownloadStreamPreferences(), audioOnly = true)
        val audioOption = audioOptions.firstOrNull()
            ?: error("The Android/WEB clients returned no direct audio-only download option.")
        val audioRange = requestRange(audioOption.stream.uri, audioOption.stream.userAgent)
        assertTrue("The audio URL did not resolve to an audio response", audioRange.contentType.startsWith("audio/"))

        val cpuMillis = Process.getElapsedCpuTime() - cpuStarted
        val elapsedMillis = SystemClock.elapsedRealtime() - wallStarted
        val pssEnded = Debug.getPss()
        val rssEnded = readRssKiB()
        System.out.println(
            "resolverRangeAcceptance=" +
                "videoItag=${videoOption.stream.itag}; videoHeight=${videoOption.stream.videoHeight}; " +
                "videoMime=${videoRange.contentType}; videoStatus=${videoRange.status}; videoBytes=${videoRange.bytes}; " +
                "audioItag=${audioOption.stream.itag}; audioMime=${audioRange.contentType}; " +
                "audioStatus=${audioRange.status}; audioBytes=${audioRange.bytes}; " +
                "cpuMs=$cpuMillis; wallMs=$elapsedMillis; pssStartKiB=$pssStarted; pssEndKiB=$pssEnded; " +
                "rssStartKiB=$rssStarted; rssEndKiB=$rssEnded",
        )
    }

    private fun requestRange(uri: String, userAgent: String): RangeResult {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Range", "bytes=0-${MAX_RANGE_BYTES - 1}")
        try {
            val status = connection.responseCode
            val input = connection.inputStream
            var received = 0
            val buffer = ByteArray(8192)
            while (received < MAX_RANGE_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RANGE_BYTES - received))
                if (count < 0) break
                received += count
            }
            input.close()
            assertEquals("Googlevideo did not honor the bounded byte-range request", HttpURLConnection.HTTP_PARTIAL, status)
            assertEquals("The bounded media range was incomplete", MAX_RANGE_BYTES, received)
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            assertTrue("The response did not identify the bounded Content-Range", contentRange.startsWith("bytes 0-${MAX_RANGE_BYTES - 1}/"))
            return RangeResult(status, received, connection.contentType.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readRssKiB(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        }
    }.getOrNull()

    private data class RangeResult(val status: Int, val bytes: Int, val contentType: String)

    companion object {
        private const val MAX_RANGE_BYTES = 65_536
        private const val REQUEST_TIMEOUT_MS = 15_000
    }
}
