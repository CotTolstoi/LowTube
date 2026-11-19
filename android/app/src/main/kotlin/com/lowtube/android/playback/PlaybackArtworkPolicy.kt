package com.lowtube.android.playback

import com.lowtube.android.model.VideoItem
import java.net.URI

/** Restricts artwork handed to external Android media controls to canonical YouTube thumbnails. */
internal object PlaybackArtworkPolicy {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun resolve(video: VideoItem): String? {
        if (!videoIdPattern.matches(video.id)) return null
        val candidate = video.thumbnailUrl.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"
        if (candidate.length > MAX_URL_LENGTH) return null

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port != -1) return null
        if (uri.host?.lowercase() !in YOUTUBE_THUMBNAIL_HOSTS) return null

        val segments = uri.path.orEmpty().split('/')
        if (segments.size < 4 || segments[1] !in YOUTUBE_THUMBNAIL_PATHS || segments[2] != video.id) return null
        return uri.toASCIIString()
    }

    private val YOUTUBE_THUMBNAIL_HOSTS = setOf("i.ytimg.com", "img.youtube.com")
    private val YOUTUBE_THUMBNAIL_PATHS = setOf("vi", "vi_webp")
    private const val MAX_URL_LENGTH = 2048
}
