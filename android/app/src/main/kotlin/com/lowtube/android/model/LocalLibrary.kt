package com.lowtube.android.model

data class LocalPlaylist(val id: String, val name: String)

data class SavedChannel(
    val id: String,
    val title: String,
    val url: String,
    val fromYouTubeAccount: Boolean = false,
    val category: String = "",
)

data class YouTubeSubscription(val channelId: String, val title: String)

data class LocalAudioTrack(val uri: String, val title: String, val mimeType: String, val addedAt: Long)

data class QueueEntry(val rowId: Long, val video: VideoItem, val continuation: String = "")

object DownloadStatus {
    const val PENDING = 1
    const val RUNNING = 2
    const val PAUSED = 4
    const val SUCCESSFUL = 8
    const val FAILED = 16
    const val CANCELLED = 32
}

data class DownloadRecord(
    val id: Long,
    val title: String,
    val status: Int,
    val reason: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String,
    val mimeType: String,
)

data class DownloadJob(
    val id: Long,
    val videoJson: String,
    val formatKey: String,
    val audioOnly: Boolean,
    val status: Int,
    val reason: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String,
    val partialPath: String,
    val fileName: String,
    val mimeType: String,
    val userAgent: String,
    val createdAt: Long,
    val maxBytesPerAttempt: Long = 0L,
)

data class WatchHistoryEntry(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val complete: Boolean,
    val updatedAt: Long,
)
