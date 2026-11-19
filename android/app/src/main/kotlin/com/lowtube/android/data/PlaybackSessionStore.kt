package com.lowtube.android.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import com.lowtube.android.model.VideoItem

/** Stores only canonical playback metadata; resolved and signed media URLs are never persisted. */
class PlaybackSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("lowtube_playback_session", Context.MODE_PRIVATE)

    fun load(): PlaybackSession? {
        val raw = preferences.getString(KEY_SESSION, null) ?: return null
        if (raw.length > MAX_SESSION_LENGTH) {
            clear()
            return null
        }
        return runCatching {
            val json = JSONObject(raw)
            val video = VideoItem(
                id = json.getString("id"),
                title = json.getString("title"),
                channel = json.optString("channel"),
                channelId = json.optString("channelId"),
                published = json.optString("published"),
                duration = json.optString("duration", "—"),
                kind = json.optString("kind", "video"),
                liveStatus = json.optString("liveStatus"),
                thumbnailUrl = json.optString("thumbnailUrl"),
                url = json.getString("url"),
                publishedAtMs = json.optLong("publishedAtMs"),
                channelAvatarUrl = json.optString("channelAvatarUrl"),
            )
            require(isSafeVideo(video))
            PlaybackSession(video, json.optLong("queueRowId").takeIf { it > 0L })
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(video: VideoItem?, queueRowId: Long?) {
        if (video == null || !isSafeVideo(video)) {
            clear()
            return
        }
        val json = JSONObject()
            .put("id", video.id)
            .put("title", video.title.take(512))
            .put("channel", video.channel.take(256))
            .put("channelId", video.channelId.take(128))
            .put("published", video.published.take(128))
            .put("duration", video.duration.take(64))
            .put("kind", video.kind)
            .put("liveStatus", video.liveStatus.take(64))
            .put("thumbnailUrl", video.thumbnailUrl.take(2048))
            .put("url", canonicalWatchUrl(video.id))
            .put("publishedAtMs", video.publishedAtMs)
            .put("channelAvatarUrl", video.channelAvatarUrl.take(2048))
        if (queueRowId != null && queueRowId > 0L) json.put("queueRowId", queueRowId)
        preferences.edit().putString(KEY_SESSION, json.toString()).commit()
    }

    fun clear() {
        preferences.edit().remove(KEY_SESSION).commit()
    }

    private fun isSafeVideo(video: VideoItem): Boolean {
        if (video.id.length != 11 || !video.id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }) return false
        if (video.kind !in PLAYABLE_KINDS) return false
        val uri = runCatching { Uri.parse(video.url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host?.lowercase() !in YOUTUBE_HOSTS) return false
        val path = uri.pathSegments
        return uri.getQueryParameter("v") == video.id ||
            (uri.host.equals("youtu.be", ignoreCase = true) && path.firstOrNull() == video.id) ||
            (path.size >= 2 && path.first() in setOf("shorts", "live", "embed") && path[1] == video.id)
    }

    private fun canonicalWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    companion object {
        private const val KEY_SESSION = "current_video_v1"
        private const val MAX_SESSION_LENGTH = 8192
        private val PLAYABLE_KINDS = setOf("video", "shorts", "live")
        private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    }
}

data class PlaybackSession(val video: VideoItem, val queueRowId: Long?)
