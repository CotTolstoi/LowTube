package com.lowtube.android.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import com.lowtube.android.model.LocalAudioTrack
import com.lowtube.android.model.VideoItem

/** Stores only canonical playback metadata; resolved and signed media URLs are never persisted. */
class PlaybackSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("lowtube_playback_session", Context.MODE_PRIVATE)

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
            val localAudio = if (video.kind == "local-audio") {
                LocalAudioTrack(
                    uri = json.getString("audioUri"),
                    title = json.getString("audioTitle"),
                    mimeType = json.getString("audioMimeType"),
                    addedAt = json.optLong("audioAddedAt"),
                ).takeIf { isSafeLocalAudio(video, it) }
                    ?: throw IllegalArgumentException("The saved audio URI is no longer available.")
            } else null
            require(if (localAudio == null) isSafeVideo(video) else true)
            PlaybackSession(video, json.optLong("queueRowId").takeIf { it > 0L }, localAudio)
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(video: VideoItem?, queueRowId: Long?, localAudioTrack: LocalAudioTrack?) {
        if (video == null || (localAudioTrack == null && !isSafeVideo(video)) ||
            (localAudioTrack != null && !isSafeLocalAudio(video, localAudioTrack))
        ) {
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
            .put("url", if (localAudioTrack == null) canonicalWatchUrl(video.id) else video.url)
            .put("publishedAtMs", video.publishedAtMs)
            .put("channelAvatarUrl", video.channelAvatarUrl.take(2048))
        if (localAudioTrack == null && queueRowId != null && queueRowId > 0L) json.put("queueRowId", queueRowId)
        if (localAudioTrack != null) {
            json.put("audioUri", localAudioTrack.uri)
                .put("audioTitle", localAudioTrack.title.take(512))
                .put("audioMimeType", localAudioTrack.mimeType.take(128))
                .put("audioAddedAt", localAudioTrack.addedAt)
        }
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

    private fun isSafeLocalAudio(video: VideoItem, track: LocalAudioTrack): Boolean {
        if (video.kind != "local-audio" || track.uri.length > MAX_AUDIO_URI_LENGTH ||
            video.id != "local:${track.uri}" || video.url != track.uri || !track.mimeType.startsWith("audio/")
        ) return false
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return false
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        return runCatching {
            appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
    }

    private fun canonicalWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    companion object {
        private const val KEY_SESSION = "current_video_v1"
        private const val MAX_SESSION_LENGTH = 8192
        private const val MAX_AUDIO_URI_LENGTH = 2048
        private val PLAYABLE_KINDS = setOf("video", "shorts", "live")
        private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    }
}

data class PlaybackSession(val video: VideoItem, val queueRowId: Long?, val localAudioTrack: LocalAudioTrack? = null)
