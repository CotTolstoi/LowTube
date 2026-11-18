package com.lowtube.android.data

import android.content.Context
import android.net.Uri
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
import java.net.HttpURLConnection
import java.net.URL

/** Bounded acceptance of the production MWEB resolver, n transform, User-Agent, and CDN range path. */
@RunWith(AndroidJUnit4::class)
class YoutubeResolverMwebAcceptanceTest {
    @Test
    fun resolvesMwebChallengeAndReadsBoundedRangeWithoutMedia3() = runBlocking {
        assumeTrue(
            "Pass -e runMwebResolverAcceptance true to run this bounded live network check",
            InstrumentationRegistry.getArguments().getString("runMwebResolverAcceptance") == "true",
        )
        val repository = InnerTubeSearchRepository(ApplicationProvider.getApplicationContext<Context>())
        val video = VideoItem(
            id = VIDEO_ID,
            title = "Public MWEB resolver fixture",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        val cpuStarted = Process.getElapsedCpuTime()
        val wallStarted = SystemClock.elapsedRealtime()
        val pssStarted = Debug.getPss()

        val stream = repository.resolvePlaybackUsingMwebForDiagnostics(video)
        assertTrue("The resolved stream did not retain the MWEB User-Agent", stream.userAgent.contains("iPad"))
        assertTrue("The resolved stream did not retain the transformed n query", Uri.parse(stream.uri).getQueryParameter("n").orEmpty().isNotBlank())

        val range = requestRange(stream.uri, stream.userAgent)
        assertEquals(HttpURLConnection.HTTP_PARTIAL, range.first)
        assertEquals(MAX_RANGE_BYTES, range.second)
        assertTrue("CDN returned a non-video content type", range.third.startsWith("video/"))

        System.out.println(
            "mwebResolverAcceptance; itag=${stream.itag}; height=${stream.videoHeight}; " +
                "nPresent=true; userAgent=iPad; httpStatus=${range.first}; bytes=${range.second}; " +
                "contentType=${range.third.substringBefore(';')}; " +
                "cpuMs=${Process.getElapsedCpuTime() - cpuStarted}; " +
                "wallMs=${SystemClock.elapsedRealtime() - wallStarted}; pssKiB=$pssStarted->${Debug.getPss()}",
        )
    }

    private fun requestRange(uri: String, userAgent: String): Triple<Int, Int, String> {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Range", "bytes=0-${MAX_RANGE_BYTES - 1}")
        try {
            val status = connection.responseCode
            val received = connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var count = 0
                while (count < MAX_RANGE_BYTES) {
                    val bytesRead = input.read(buffer, 0, minOf(buffer.size, MAX_RANGE_BYTES - count))
                    if (bytesRead < 0) break
                    count += bytesRead
                }
                count
            }
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            assertTrue("The response did not identify the bounded Content-Range", contentRange.startsWith("bytes 0-${MAX_RANGE_BYTES - 1}/"))
            return Triple(status, received, connection.contentType.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val VIDEO_ID = "aqz-KE-bpKQ"
        private const val MAX_RANGE_BYTES = 65_536
        private const val REQUEST_TIMEOUT_MS = 15_000
    }
}
