package com.lowtube.android.data

import com.lowtube.android.model.YouTubeSubscription
import com.lowtube.android.model.YouTubePlaylist
import com.lowtube.android.model.VideoItem
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GoogleAccountProfile(
    val subject: String,
    val email: String,
    val displayName: String,
    val pictureUrl: String = "",
)

internal object GoogleProfileAvatarPolicy {
    private val trustedHosts = setOf("lh3.googleusercontent.com", "lh4.googleusercontent.com")
    private const val MAX_PICTURE_URL_LENGTH = 2048

    fun validate(raw: String): String {
        if (raw.isBlank() || raw.length > MAX_PICTURE_URL_LENGTH) return ""
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return ""
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase() !in trustedHosts ||
            !uri.userInfo.isNullOrBlank() ||
            uri.port !in listOf(-1, 443) ||
            !uri.path.orEmpty().startsWith("/")) return ""
        return uri.toString()
    }
}

/** Reads the signed-in user's channel subscriptions through the official YouTube Data API. */
class YouTubeDataApiRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun userProfile(accessToken: String): GoogleAccountProfile = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH) {
            "Google authorization is missing. Try signing in again."
        }
        val request = Request.Builder()
            .url(USER_INFO_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google could not return the signed-in account profile (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("Google returned an empty account profile.")
            if (body.contentLength() > MAX_PROFILE_BYTES) throw IOException("Google returned an oversized account profile.")
            val json = JSONObject(readBounded(body.byteStream(), MAX_PROFILE_BYTES))
            val subject = json.optString("sub").trim()
            val email = json.optString("email").trim().take(MAX_EMAIL_LENGTH)
            val displayName = json.optString("name").trim().take(MAX_NAME_LENGTH)
            val pictureUrl = GoogleProfileAvatarPolicy.validate(json.optString("picture"))
            if (subject.isBlank() || email.isBlank() || !json.optBoolean("email_verified", false)) {
                throw IOException("Google did not return a verified account email.")
            }
            GoogleAccountProfile(subject, email, displayName, pictureUrl)
        }
    }

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

    /** Lists the signed-in user's own playlists. The caller decides whether to retain results in memory. */
    suspend fun userPlaylists(accessToken: String): List<YouTubePlaylist> = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH) {
            "Google authorization is missing. Load playlists again."
        }
        val result = LinkedHashMap<String, YouTubePlaylist>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            if (++pageCount > MAX_PLAYLIST_PAGES) throw IOException("This account has too many playlists to load in one request.")
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("www.googleapis.com")
                .addPathSegments("youtube/v3/playlists")
                .addQueryParameter("part", "snippet,contentDetails")
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
                val body = response.body ?: throw IOException("YouTube returned an empty playlists response.")
                if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("YouTube returned an oversized playlists page.")
                val json = JSONObject(readBounded(body.byteStream(), MAX_PAGE_BYTES))
                val items = json.optJSONArray("items") ?: throw IOException("YouTube returned an invalid playlists response.")
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: throw IOException("YouTube returned an invalid playlist entry.")
                    val id = item.optString("id")
                    val snippet = item.optJSONObject("snippet") ?: throw IOException("YouTube returned an invalid playlist entry.")
                    val title = snippet.optString("title").trim().take(MAX_TITLE_LENGTH)
                    val videoCount = item.optJSONObject("contentDetails")?.optInt("itemCount", 0)?.coerceAtLeast(0) ?: 0
                    if (!PLAYLIST_ID.matches(id) || title.isBlank()) throw IOException("YouTube returned an invalid playlist entry.")
                    result[id] = YouTubePlaylist(id, title, videoCount)
                    if (result.size > MAX_PLAYLISTS) throw IOException("This account has too many playlists to load.")
                }
                pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
            }
        } while (pageToken != null)
        result.values.toList()
    }

    /** Reads one authenticated playlist, including private playlists owned by the signed-in user. */
    suspend fun playlistVideos(accessToken: String, playlistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH) {
            "Google authorization is missing. Open the playlist again."
        }
        require(PLAYLIST_ID.matches(playlistId)) { "This YouTube playlist has an invalid ID." }
        val result = ArrayList<VideoItem>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            if (++pageCount > MAX_PLAYLIST_PAGES) throw IOException("This playlist has too many videos to load in one request.")
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("www.googleapis.com")
                .addPathSegments("youtube/v3/playlistItems")
                .addQueryParameter("part", "snippet,contentDetails")
                .addQueryParameter("playlistId", playlistId)
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
                val body = response.body ?: throw IOException("YouTube returned an empty playlist-items response.")
                if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("YouTube returned an oversized playlist page.")
                val json = JSONObject(readBounded(body.byteStream(), MAX_PAGE_BYTES))
                val items = json.optJSONArray("items") ?: throw IOException("YouTube returned an invalid playlist-items response.")
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: throw IOException("YouTube returned an invalid playlist item.")
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val videoId = snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                    val title = snippet.optString("title").trim().take(MAX_TITLE_LENGTH)
                    if (!VIDEO_ID.matches(videoId) || title.isBlank() || title == "[Deleted video]" || title == "[Private video]") continue
                    val channelId = snippet.optString("videoOwnerChannelId").takeIf(CHANNEL_ID::matches).orEmpty()
                    result += VideoItem(
                        id = videoId,
                        title = title,
                        channel = snippet.optString("videoOwnerChannelTitle").trim().take(MAX_TITLE_LENGTH),
                        channelId = channelId,
                        kind = "video",
                        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        url = "https://www.youtube.com/watch?v=$videoId",
                    )
                    if (result.size > MAX_PLAYLIST_VIDEOS) throw IOException("This playlist has too many available videos to load.")
                }
                pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
            }
        } while (pageToken != null)
        result.toList()
    }

    suspend fun setSubscription(accessToken: String, channelId: String, subscribe: Boolean) = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank() && accessToken.length <= MAX_ACCESS_TOKEN_LENGTH) {
            "Google authorization is missing. Try again."
        }
        require(CHANNEL_ID.matches(channelId)) { "This channel does not have a valid YouTube channel ID." }
        if (subscribe) subscribe(accessToken, channelId) else unsubscribe(accessToken, channelId)
    }

    private fun subscribe(accessToken: String, channelId: String) {
        val body = JSONObject()
            .put("snippet", JSONObject().put("resourceId", JSONObject()
                .put("kind", "youtube#channel")
                .put("channelId", channelId)))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(subscriptionCollectionUrl("snippet"))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readErrorBody(response)
                if (response.code == 400 && errorBody.contains("subscriptionDuplicate", ignoreCase = true)) return
                throw IOException(subscriptionMutationError(response.code, errorBody))
            }
        }
    }

    private fun unsubscribe(accessToken: String, channelId: String) {
        val lookupUrl = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("youtube/v3/subscriptions")
            .addQueryParameter("part", "id,snippet")
            .addQueryParameter("mine", "true")
            .addQueryParameter("forChannelId", channelId)
            .addQueryParameter("maxResults", "50")
            .build()
        val lookupRequest = Request.Builder()
            .url(lookupUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val subscriptionId = client.newCall(lookupRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException(subscriptionMutationError(response.code, readErrorBody(response)))
            val body = response.body ?: throw IOException("YouTube returned an empty subscription lookup.")
            if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("YouTube returned an oversized subscription lookup.")
            val items = JSONObject(readBounded(body.byteStream(), MAX_PAGE_BYTES)).optJSONArray("items")
                ?: throw IOException("YouTube returned an invalid subscription lookup.")
            (0 until items.length()).asSequence()
                .mapNotNull { index -> items.optJSONObject(index) }
                .firstOrNull { item ->
                    item.optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("channelId") == channelId
                }
                ?.optString("id")
                .orEmpty()
        }
        if (subscriptionId.isBlank()) return
        val deleteUrl = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("youtube/v3/subscriptions")
            .addQueryParameter("id", subscriptionId)
            .build()
        val deleteRequest = Request.Builder()
            .url(deleteUrl)
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        client.newCall(deleteRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException(subscriptionMutationError(response.code, readErrorBody(response)))
        }
    }

    private fun subscriptionCollectionUrl(part: String): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("www.googleapis.com")
        .addPathSegments("youtube/v3/subscriptions")
        .addQueryParameter("part", part)
        .build()

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

    private fun subscriptionMutationError(statusCode: Int, body: String): String {
        if (statusCode == 400 && body.contains("subscriptionDuplicate", ignoreCase = true)) {
            return "This channel is already subscribed to. Sync your YouTube subscriptions and try again."
        }
        return when (statusCode) {
            401 -> "YouTube authorization expired. Try subscribing again."
            403 -> "YouTube denied this subscription change. Check account permission and YouTube API access."
            404 -> "This YouTube subscription could not be found. Sync and try again."
            else -> "YouTube could not change the subscription (HTTP $statusCode). Try again later."
        }
    }

    private fun readErrorBody(response: okhttp3.Response): String = response.body?.let { body ->
        runCatching { readBounded(body.byteStream(), MAX_ERROR_BYTES) }.getOrDefault("")
    }.orEmpty()

    companion object {
        private const val USER_INFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 100
        private const val MAX_SUBSCRIPTIONS = 5000
        private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_PROFILE_BYTES = 64 * 1024
        private const val MAX_ERROR_BYTES = 8 * 1024
        private const val MAX_EMAIL_LENGTH = 320
        private const val MAX_NAME_LENGTH = 160
        private const val MAX_TITLE_LENGTH = 250
        private const val MAX_ACCESS_TOKEN_LENGTH = 8192
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{1,256}$")
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private const val MAX_PLAYLIST_PAGES = 100
        private const val MAX_PLAYLISTS = PAGE_SIZE * MAX_PLAYLIST_PAGES
        private const val MAX_PLAYLIST_VIDEOS = PAGE_SIZE * MAX_PLAYLIST_PAGES
    }
}
