package com.lowtube.android.data

import com.lowtube.android.model.YouTubeSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads the signed-in user's channel subscriptions through the official YouTube Data API. */
class YouTubeDataApiRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun subscriptions(accessToken: String): List<YouTubeSubscription> = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH) { "Google authorization is missing. Try syncing again." }
        val result = LinkedHashMap<String, YouTubeSubscription>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            if (++pageCount > MAX_PAGES) throw IOException("This account has too many subscriptions to sync in one request.")
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("www.googleapis.com")
                .addPathSegments("youtube/v3/subscriptions")
                .addQueryParameter("part", "snippet")
                .addQueryParameter("mine", "true")
                .addQueryParameter("maxResults", PAGE_SIZE.toString())
                .apply { if (!pageToken.isNullOrBlank()) addQueryParameter("pageToken", pageToken) }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(apiError(response.code))
                val body = response.body ?: throw IOException("YouTube returned an empty subscriptions response.")
                if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("YouTube returned an oversized subscriptions page.")
                val json = JSONObject(readBounded(body.byteStream(), MAX_PAGE_BYTES))
                val items = json.optJSONArray("items") ?: throw IOException("YouTube returned an invalid subscriptions response.")
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: throw IOException("YouTube returned an invalid subscription entry.")
                    val snippet = item.optJSONObject("snippet") ?: throw IOException("YouTube returned an invalid subscription entry.")
                    val channelId = snippet.optJSONObject("resourceId")?.optString("channelId").orEmpty()
                    val title = snippet.optString("title").trim().take(MAX_TITLE_LENGTH)
                    if (!CHANNEL_ID.matches(channelId) || title.isBlank()) throw IOException("YouTube returned an invalid subscription entry.")
                    result[channelId] = YouTubeSubscription(channelId, title)
                    if (result.size > MAX_SUBSCRIPTIONS) throw IOException("This account has too many subscriptions to sync.")
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
        } while (pageToken != null)
        result.values.toList()
    }

    suspend fun revokeAccess(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() || accessToken.length > MAX_ACCESS_TOKEN_LENGTH) return@withContext false
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke")
            .post(FormBody.Builder().add("token", accessToken).build())
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("YouTube returned an oversized subscriptions page.")
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun apiError(statusCode: Int): String = when (statusCode) {
        401 -> "YouTube authorization expired. Sync again to renew access."
        403 -> "YouTube denied access. Check API availability and the youtube.readonly permission."
        404 -> "The YouTube subscriptions endpoint is unavailable."
        else -> "YouTube could not load subscriptions (HTTP $statusCode). Try again later."
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 100
        private const val MAX_SUBSCRIPTIONS = 5000
        private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_TITLE_LENGTH = 250
        private const val MAX_ACCESS_TOKEN_LENGTH = 8192
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    }
}
