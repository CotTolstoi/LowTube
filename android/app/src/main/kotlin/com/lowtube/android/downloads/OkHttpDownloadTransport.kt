package com.lowtube.android.downloads

import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal data class TransferProgress(val downloaded: Long, val total: Long)

/** Shared bounded-memory Range transport used by production jobs and the opt-in 64 KiB acceptance. */
internal class OkHttpDownloadTransport(private val client: OkHttpClient) {
    suspend fun download(
        stream: ResolvedVideoStream,
        video: VideoItem,
        partialFile: File,
        rateLimitKiB: Int,
        shouldCancel: () -> Boolean,
        maxBytesThisAttempt: Long = Long.MAX_VALUE,
        requirePartialResponse: Boolean = false,
        allowLoopbackHttpForDebugTest: Boolean = false,
        onCallCreated: (Call?) -> Unit = {},
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferProgress {
        if (maxBytesThisAttempt <= 0L) throw IllegalArgumentException("Transfer byte limit must be positive.")
        val streamUri = android.net.Uri.parse(stream.uri)
        val host = streamUri.host?.lowercase().orEmpty()
        val isAllowedLoopbackTest = allowLoopbackHttpForDebugTest && streamUri.scheme == "http" && host in LOOPBACK_HOSTS
        if (!isAllowedLoopbackTest && (streamUri.scheme != "https" || !(host == "googlevideo.com" || host.endsWith(".googlevideo.com")))) {
            throw IOException("YouTube returned a stream address that LowTube cannot safely download.")
        }
        val parent = partialFile.parentFile ?: throw IOException("The partial file must use an absolute path.")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Could not prepare private download storage.")
        var downloaded = if (partialFile.isFile) partialFile.length() else 0L
        val rangeEnd = if (maxBytesThisAttempt == Long.MAX_VALUE) null else downloaded + maxBytesThisAttempt - 1
        val requestBuilder = Request.Builder().url(stream.uri).header("User-Agent", stream.userAgent)
            .header("Referer", "https://www.youtube.com/watch?v=${video.id}").header("Origin", "https://www.youtube.com").header("Accept", "*/*")
        if (downloaded > 0 || rangeEnd != null) requestBuilder.header("Range", "bytes=$downloaded-${rangeEnd ?: ""}")
        val transferStartNs = System.nanoTime()
        var sessionBytes = 0L
        val call = client.newCall(requestBuilder.build())
        onCallCreated(call)
        if (shouldCancel()) call.cancel()
        try {
            call.execute().use { response ->
                if (!(response.isSuccessful && (response.code == 200 || response.code == 206))) throw IOException("Download server returned HTTP ${response.code} ${response.message}.")
                if (requirePartialResponse && response.code != 206) throw IOException("Download server did not honor the bounded HTTP Range request (HTTP ${response.code}).")
                val finalUrl = response.request.url
                val finalHost = finalUrl.host.lowercase()
            val finalLoopbackTest = allowLoopbackHttpForDebugTest && finalUrl.scheme == "http" && finalHost in LOOPBACK_HOSTS
            if (!finalLoopbackTest && (finalUrl.scheme != "https" || !(finalHost == "googlevideo.com" || finalHost.endsWith(".googlevideo.com")))) throw IOException("Download server redirected to an untrusted address.")
                val body = response.body ?: throw IOException("Download server returned an empty response.")
                if (downloaded > 0 && response.code == 200) { partialFile.delete(); downloaded = 0 }
                if (response.code == 206) {
                    val start = Regex("bytes (\\d+)-").find(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (start != downloaded) throw IOException("Download server resumed at an unexpected byte offset.")
                }
                val contentLength = body.contentLength().coerceAtLeast(0)
                val rangeTotal = Regex("bytes \\d+-\\d+/(\\d+)").find(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                val total = rangeTotal ?: if (response.code == 206) downloaded + contentLength else contentLength
                val output = FileOutputStream(partialFile, downloaded > 0).buffered()
                body.byteStream().use { input -> output.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgress = System.nanoTime()
                    while (true) {
                        if (shouldCancel()) throw CancellationException("Cancelled by user.")
                        val allowance = maxBytesThisAttempt - sessionBytes
                        if (allowance <= 0) break
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), allowance).toInt())
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        downloaded += count
                        sessionBytes += count
                        val now = System.nanoTime()
                        if (rateLimitKiB > 0) {
                            val byteRate = rateLimitKiB.coerceAtLeast(0) * 1024L
                            val allowedNs = (sessionBytes.toDouble() * 1_000_000_000.0 / byteRate.toDouble()).toLong()
                            val elapsedNs = now - transferStartNs
                            if (allowedNs > elapsedNs) delay(((allowedNs - elapsedNs) / 1_000_000L).coerceAtMost(1000))
                        }
                        if (now - lastProgress >= 750_000_000L) { out.flush(); onProgress(TransferProgress(downloaded, total)); lastProgress = now }
                    }
                    out.flush()
                } }
                onProgress(TransferProgress(downloaded, total))
                if (total > 0 && downloaded < total && sessionBytes < maxBytesThisAttempt && maxBytesThisAttempt == Long.MAX_VALUE) {
                    throw IOException("Download ended early at $downloaded of $total bytes.")
                }
                return TransferProgress(downloaded, total)
            }
        } finally {
            onCallCreated(null)
        }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
    }
}
