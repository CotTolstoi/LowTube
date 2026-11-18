package com.lowtube.android.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.regex.Pattern

/** A bounded, in-memory cache for the exact YouTube player script version used by a video. */
internal class YoutubePlayerScriptCache(
    private val fetchSource: (String) -> String = ::fetchYoutubePlayerScript,
) {
    data class PlayerScript(val url: String, val source: String, val signatureTimestamp: Int?)

    @Volatile
    private var cached: PlayerScript? = null

    fun load(rawUrl: String): PlayerScript {
        val url = normalizeUrl(rawUrl) ?: throw IOException("YouTube returned an invalid player script URL.")
        cached?.takeIf { it.url == url }?.let { return it }
        synchronized(this) {
            cached?.takeIf { it.url == url }?.let { return it }
            val source = fetchSource(url)
            if (source.isBlank()) throw IOException("YouTube returned an empty player script.")
            if (source.length > MAX_SCRIPT_BYTES) throw IOException("YouTube player script is larger than 8 MB.")
            return PlayerScript(url, source, extractSignatureTimestamp(source)).also { cached = it }
        }
    }

    companion object {
        internal const val TIMEOUT_MS = 12_000
        internal const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
        internal const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val SIGNATURE_TIMESTAMP = Pattern.compile("(?:signatureTimestamp|\\bsts)\\s*[:=]\\s*(\\d{5,6})")

        internal fun normalizeUrl(rawUrl: String): String? = runCatching {
            val normalized = URI("https://www.youtube.com").resolve(rawUrl.trim()).normalize()
            val path = normalized.path.orEmpty()
            if (normalized.scheme != "https" || normalized.host?.lowercase() !in setOf("youtube.com", "www.youtube.com")) return null
            if (normalized.userInfo != null || normalized.port !in setOf(-1, 443) || normalized.fragment != null) return null
            if (!path.startsWith("/s/player/") || !path.endsWith("/base.js")) return null
            normalized.toASCIIString()
        }.getOrNull()

        internal fun extractSignatureTimestamp(source: String): Int? =
            SIGNATURE_TIMESTAMP.matcher(source).let { matcher ->
                if (matcher.find()) matcher.group(1)?.toIntOrNull()?.takeIf { it in 10_000..999_999 } else null
            }
    }
}

private fun fetchYoutubePlayerScript(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false
        connectTimeout = YoutubePlayerScriptCache.TIMEOUT_MS
        readTimeout = YoutubePlayerScriptCache.TIMEOUT_MS
        setRequestProperty("User-Agent", YoutubePlayerScriptCache.USER_AGENT)
        setRequestProperty("Accept", "application/javascript, text/javascript, */*;q=0.8")
        setRequestProperty("Referer", "https://www.youtube.com/")
    }
    try {
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("YouTube player script request failed with HTTP $status.")
        val source = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) throw IOException("YouTube player script is larger than 8 MB.")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        if (source.isBlank()) throw IOException("YouTube returned an empty player script.")
        return source
    } finally {
        connection.disconnect()
    }
}
