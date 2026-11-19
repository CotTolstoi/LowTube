package com.lowtube.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Experimental, read-only account history adapter for YouTube's private InnerTube FEhistory browse.
 * OAuth tokens are request-scoped and never retained; this endpoint is not part of the supported
 * YouTube Data API and may stop accepting this client or authorization flow without notice.
 */
internal class YouTubeAccountHistoryRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val pageParser: InnerTubeSearchRepository = InnerTubeSearchRepository(),
) {
    @Volatile private var cachedConfiguration: Configuration? = null

    suspend fun page(accessToken: String, continuation: String? = null, language: String = "en"):
        com.lowtube.android.model.SearchPage = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH &&
            accessToken.none { it == '\r' || it == '\n' }) {
            "Google authorization is missing. Sign in and grant read-only YouTube access."
        }
        val token = continuation?.trim()?.takeIf(String::isNotEmpty)
        require(token == null || token.length <= MAX_CONTINUATION_LENGTH) {
            "YouTube returned an invalid history continuation. Reload the history page."
        }
        val configuration = configuration(language)
        val body = historyBrowseBody(configuration, language, token)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(YOUTUBE_HOST)
            .addPathSegments("youtubei/v1/browse")
            .addQueryParameter("key", configuration.apiKey)
            .addQueryParameter("prettyPrint", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", configuration.clientVersion)
            .apply { if (configuration.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", configuration.visitorData) }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.request.url.host != YOUTUBE_HOST || response.request.url.scheme != "https") {
                throw IOException("YouTube redirected the account-history request to an unexpected host.")
            }
            if (!response.isSuccessful) throw historyHttpError(response.code)
            val responseBody = response.body ?: throw IOException("YouTube returned an empty account-history page.")
            if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw IOException("YouTube returned an oversized account-history page.")
            val root = JSONObject(readBounded(responseBody.byteStream(), MAX_RESPONSE_BYTES))
            val page = pageParser.parseSearchPage(root)
            if (page.items.size > MAX_PAGE_ITEMS) throw IOException("YouTube returned too many account-history entries in one page.")
            if (page.continuation?.length?.let { it > MAX_CONTINUATION_LENGTH } == true) {
                throw IOException("YouTube returned an invalid account-history continuation.")
            }
            page
        }
    }

    private fun configuration(language: String): Configuration {
        val now = System.currentTimeMillis()
        cachedConfiguration?.takeIf { now - it.fetchedAtMs < CONFIGURATION_TTL_MS }?.let { return it }
        val request = Request.Builder()
            .url("https://$YOUTUBE_HOST/?hl=${language.take(8)}")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        val loaded = client.newCall(request).execute().use { response ->
            if (response.request.url.host != YOUTUBE_HOST || response.request.url.scheme != "https") {
                throw IOException("YouTube redirected the account-history configuration request to an unexpected host.")
            }
            if (!response.isSuccessful) throw IOException("YouTube configuration request failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("YouTube returned an empty configuration response.")
            if (body.contentLength() > MAX_CONFIGURATION_BYTES) throw IOException("YouTube configuration response is oversized.")
            val html = readBounded(body.byteStream(), MAX_CONFIGURATION_BYTES)
            val config = embeddedObject(html, "ytcfg.set(")
                ?: throw IOException("YouTube catalogue configuration is unavailable. Retry later.")
            val clientConfig = config.optJSONObject("INNERTUBE_CONTEXT")?.optJSONObject("client")
            val clientVersion = config.optString("INNERTUBE_CLIENT_VERSION", clientConfig?.optString("clientVersion").orEmpty())
            val apiKey = config.optString("INNERTUBE_API_KEY")
            val visitorData = config.optString("VISITOR_DATA", clientConfig?.optString("visitorData").orEmpty())
            if (!CLIENT_VERSION.matches(clientVersion) || !API_KEY.matches(apiKey)) {
                throw IOException("YouTube catalogue configuration is incomplete. Retry later.")
            }
            Configuration(clientVersion, apiKey, visitorData.take(MAX_VISITOR_DATA_LENGTH), now)
        }
        cachedConfiguration = loaded
        return loaded
    }

    internal fun historyBrowseBody(configuration: Configuration, language: String, continuation: String?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", configuration.clientVersion)
            .put("hl", language.take(8))
            .put("gl", "US")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        if (configuration.visitorData.isNotBlank()) clientContext.put("visitorData", configuration.visitorData)
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
        if (continuation.isNullOrBlank()) body.put("browseId", HISTORY_BROWSE_ID)
        else body.put("continuation", continuation)
        return body
    }

    private fun embeddedObject(source: String, marker: String): JSONObject? {
        var offset = 0
        while (offset < source.length) {
            val markerAt = source.indexOf(marker, offset)
            if (markerAt < 0) return null
            var start = markerAt + marker.length
            while (start < source.length && source[start].isWhitespace()) start++
            if (start >= source.length || source[start] != '{') {
                offset = start
                continue
            }
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until source.length) {
                val char = source[index]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) {
                        runCatching { JSONObject(source.substring(start, index + 1)) }.getOrNull()?.let { return it }
                        offset = index + 1
                        break
                    }
                }
            }
            if (offset <= markerAt) return null
        }
        return null
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): String = input.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw IOException("YouTube account-history response exceeds its size limit.")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    internal data class Configuration(
        val clientVersion: String,
        val apiKey: String,
        val visitorData: String,
        val fetchedAtMs: Long = 0L,
    )

    private fun historyHttpError(status: Int): IOException = when (status) {
        401, 403 -> IOException("YouTube did not accept account-history authorization (HTTP $status). Verify Google consent and retry.")
        429, 503 -> IOException("YouTube temporarily limited account-history requests (HTTP $status). Retry later.")
        else -> IOException("YouTube account-history request failed (HTTP $status).")
    }

    private companion object {
        const val YOUTUBE_HOST = "www.youtube.com"
        const val HISTORY_BROWSE_ID = "FEhistory"
        const val MAX_ACCESS_TOKEN_LENGTH = 16_384
        const val MAX_CONTINUATION_LENGTH = 16_384
        const val MAX_VISITOR_DATA_LENGTH = 4_096
        const val MAX_CONFIGURATION_BYTES = 4 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_PAGE_ITEMS = 100
        const val CONFIGURATION_TTL_MS = 15 * 60 * 1000L
        val API_KEY = Regex("^[A-Za-z0-9_-]{20,256}$")
        val CLIENT_VERSION = Regex("^[0-9.]{1,64}$")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
