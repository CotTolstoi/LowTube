package com.lowtube.android.model

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelId: String = "",
    val published: String = "",
    val duration: String = "—",
    val kind: String = "video",
    val liveStatus: String = "",
    val thumbnailUrl: String = "",
    val url: String,
    val publishedAtMs: Long = 0L,
    val channelAvatarUrl: String = "",
)

data class SearchPage(
    val items: List<VideoItem>,
    val continuation: String?,
    val playlistTitle: String? = null,
    val channelHeader: ChannelHeader? = null,
)

data class ChannelHeader(
    val channelId: String = "",
    val title: String,
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val subscriberCountText: String = "",
    val description: String = "",
)

data class ResolvedVideoStream(
    val uri: String,
    val mimeType: String,
    val video: VideoItem,
    val userAgent: String,
    val durationMs: Long,
    val videoHeight: Int = 0,
    val fps: Double = 0.0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val itag: Int = 0,
    val isAudioOnly: Boolean = false,
    val isDashManifest: Boolean = false,
    val isHlsManifest: Boolean = false,
    val isMuxed: Boolean = false,
)

/** One directly downloadable native source format; signed URLs stay in memory only. */
data class DownloadFormatOption(
    val key: String,
    val stream: ResolvedVideoStream,
    val bitrateKbps: Int = 0,
    val container: String,
    val audioStream: ResolvedVideoStream? = null,
    /** Nonzero requests local MP3 transcode at this bitrate instead of keeping the source container. */
    val targetMp3BitrateKbps: Int = 0,
) {
    val audioOnly: Boolean get() = stream.isAudioOnly
    val requiresMuxing: Boolean get() = audioStream != null
}
