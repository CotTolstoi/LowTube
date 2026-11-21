package com.lowtube.android.data

import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.QueueEntry
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.WatchHistoryEntry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Explicit, account-free backup format for the user's local LowTube library. */
internal object LocalLibraryTransferRepository {
    data class PlaylistSnapshot(val id: String, val name: String, val videos: List<VideoItem>)

    data class Snapshot(
        val channels: List<SavedChannel>,
        val playlists: List<PlaylistSnapshot>,
        val history: List<WatchHistoryEntry>,
        val queue: List<QueueEntry>? = null,
    )

    fun encode(snapshot: Snapshot): String {
        validate(snapshot)
        val json = JSONObject()
            .put("Format", FORMAT)
            .put("SchemaVersion", SCHEMA_VERSION)
            .put("Channels", JSONArray().apply {
                snapshot.channels.forEach { channel ->
                    put(JSONObject()
                        .put("Id", channel.id)
                        .put("Title", channel.title)
                        .put("Category", channel.category))
                }
            })
            .put("Playlists", JSONArray().apply {
                snapshot.playlists.forEach { playlist ->
                    put(JSONObject()
                        .put("Id", playlist.id)
                        .put("Name", playlist.name)
                        .put("Videos", videoArray(playlist.videos)))
                }
            })
            .put("WatchHistory", JSONArray().apply {
                snapshot.history.forEach { entry ->
                    put(videoObject(entry.video)
                        .put("PositionMs", entry.positionMs)
                        .put("DurationMs", entry.durationMs)
                        .put("Complete", entry.complete)
                        .put("UpdatedAt", entry.updatedAt))
                }
            })
            .put("Queue", snapshot.queue?.let { entries -> JSONArray().apply {
                entries.forEach { entry ->
                    put(queueVideoObject(entry.video).put("Continuation", entry.continuation))
                }
            } } ?: JSONObject.NULL)
            .toString(2)
        require(json.length <= MAX_JSON_CHARS) { "Local library backup exceeds the supported size." }
        return json
    }

    fun decode(json: String): Snapshot {
        require(json.length <= MAX_JSON_CHARS) { "Local library backup is larger than 16 MiB." }
        val root = try {
            JSONObject(json)
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Local library backup is not valid JSON.", failure)
        }
        require(root.optString("Format") == FORMAT) { "This is not a LowTube local library backup." }
        require(root.optInt("SchemaVersion", -1) == SCHEMA_VERSION) { "This LowTube backup version is not supported." }

        val channels = root.requiredArray("Channels").let { array ->
            require(array.length() <= LocalLibraryRepository.MAX_CHANNELS) { "Backup contains too many local channels." }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(SavedChannel(
                        id = item.requiredString("Id"),
                        title = item.requiredString("Title"),
                        url = "https://www.youtube.com/channel/${item.requiredString("Id")}",
                        category = item.optString("Category", ""),
                    ))
                }
            }
        }
        var totalVideos = 0
        val playlists = root.requiredArray("Playlists").let { array ->
            require(array.length() <= LocalLibraryRepository.MAX_PLAYLISTS) { "Backup contains too many playlists." }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val videos = item.requiredArray("Videos").let { videoArray ->
                        require(videoArray.length() <= LocalLibraryRepository.MAX_ITEMS_PER_PLAYLIST) { "A backup playlist contains too many videos." }
                        totalVideos += videoArray.length()
                        require(totalVideos <= MAX_TOTAL_VIDEO_ROWS) { "Local library backup contains too many video records." }
                        buildList { for (videoIndex in 0 until videoArray.length()) add(decodeVideo(videoArray.getJSONObject(videoIndex))) }
                    }
                    add(PlaylistSnapshot(item.requiredString("Id"), item.requiredString("Name"), videos))
                }
            }
        }
        val history = root.requiredArray("WatchHistory").let { array ->
            require(array.length() <= LocalLibraryRepository.MAX_HISTORY_ITEMS) { "Backup contains too many watch history entries." }
            totalVideos += array.length()
            require(totalVideos <= MAX_TOTAL_VIDEO_ROWS) { "Local library backup contains too many video records." }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val video = decodeVideo(item)
                    val position = item.requiredLong("PositionMs")
                    val duration = item.requiredLong("DurationMs")
                    val updatedAt = item.requiredLong("UpdatedAt")
                    require(position >= 0L && duration >= 0L && updatedAt >= 0L) { "Backup contains invalid watch progress." }
                    require(item.has("Complete") && item.get("Complete") is Boolean) { "Backup contains invalid watch progress." }
                    add(WatchHistoryEntry(video, position, duration, item.getBoolean("Complete"), updatedAt))
                }
            }
        }
        // Queue was added as an optional field in v1. Older backups leave the current queue untouched.
        val queue = (if (root.has("Queue") && !root.isNull("Queue")) root.requiredArray("Queue") else null)?.let { array ->
            require(array.length() <= LocalLibraryRepository.MAX_QUEUE_ITEMS) { "Backup contains too many queued items." }
            totalVideos += array.length()
            require(totalVideos <= MAX_TOTAL_VIDEO_ROWS + LocalLibraryRepository.MAX_QUEUE_ITEMS) { "Local library backup contains too many video records." }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val video = decodeQueueVideo(item)
                    val continuation = item.optString("Continuation", "")
                    require(continuation.length <= MAX_QUEUE_CONTINUATION_LENGTH) { "The playlist continuation is too large." }
                    require(video.kind == "playlist" || continuation.isEmpty()) { "Backup contains an invalid queue continuation." }
                    add(QueueEntry(rowId = 0L, video = video, continuation = continuation))
                }
            }
        }
        val snapshot = Snapshot(channels, playlists, history, queue)
        validate(snapshot)
        return snapshot
    }

    internal fun validate(snapshot: Snapshot) {
        require(snapshot.channels.size <= LocalLibraryRepository.MAX_CHANNELS)
        require(snapshot.playlists.size <= LocalLibraryRepository.MAX_PLAYLISTS)
        require(snapshot.history.size <= LocalLibraryRepository.MAX_HISTORY_ITEMS)
        require((snapshot.queue?.size ?: 0) <= LocalLibraryRepository.MAX_QUEUE_ITEMS) { "Backup contains too many queued items." }
        require(snapshot.playlists.sumOf { it.videos.size } + snapshot.history.size <= MAX_TOTAL_VIDEO_ROWS) {
            "Local library backup contains too many video records."
        }
        require(snapshot.channels.map { it.id }.distinct().size == snapshot.channels.size) { "Backup contains duplicate channels." }
        require(snapshot.playlists.map { it.id }.distinct().size == snapshot.playlists.size) { "Backup contains duplicate playlists." }
        require(snapshot.playlists.map { it.name.trim().lowercase() }.distinct().size == snapshot.playlists.size) { "Backup contains duplicate playlist names." }
        require(snapshot.history.map { it.video.id }.distinct().size == snapshot.history.size) { "Backup contains duplicate watch history entries." }
        require(snapshot.channels.all {
            CHANNEL_ID.matches(it.id) && it.title.isNotBlank() && it.title.length <= MAX_CHANNEL_TITLE_LENGTH && it.category.length <= MAX_CATEGORY_LENGTH
        }) { "Backup contains an invalid local channel." }
        snapshot.playlists.forEach { playlist ->
            require(PLAYLIST_ID.matches(playlist.id)) { "Backup contains an invalid playlist ID." }
            require(playlist.name.trim().isNotEmpty() && playlist.name.length <= MAX_PLAYLIST_NAME_LENGTH) { "Backup contains an invalid playlist name." }
            require(playlist.videos.size <= LocalLibraryRepository.MAX_ITEMS_PER_PLAYLIST) { "A backup playlist contains too many videos." }
            playlist.videos.forEach(::validateVideo)
        }
        snapshot.history.forEach { entry ->
            validateVideo(entry.video)
            require(entry.positionMs >= 0L && entry.durationMs >= 0L && entry.updatedAt >= 0L) { "Backup contains invalid watch progress." }
        }
        snapshot.queue.orEmpty().forEach { entry ->
            validateQueueVideo(entry.video)
            require(entry.continuation.length <= MAX_QUEUE_CONTINUATION_LENGTH) { "The playlist continuation is too large." }
            require(entry.video.kind == "playlist" || entry.continuation.isEmpty()) { "Backup contains an invalid queue continuation." }
        }
    }

    private fun validateVideo(video: VideoItem) {
        require(VIDEO_ID.matches(video.id) && video.kind != "playlist") { "Backup contains an invalid video." }
        require(video.title.isNotBlank() && video.title.length <= MAX_VIDEO_TITLE_LENGTH)
        require(video.channel.length <= MAX_CHANNEL_TITLE_LENGTH && video.channelId.length <= MAX_CHANNEL_ID_LENGTH)
        require(video.channelId.isBlank() || CHANNEL_ID.matches(video.channelId)) { "Backup contains an invalid channel ID." }
        require(video.published.length <= MAX_PUBLISHED_LENGTH && video.duration.length <= MAX_DURATION_LENGTH)
        require(video.publishedAtMs >= 0L)
    }

    private fun videoArray(videos: List<VideoItem>) = JSONArray().apply { videos.forEach { put(videoObject(it)) } }

    private fun queueVideoObject(video: VideoItem): JSONObject {
        validateQueueVideo(video)
        return videoObject(video)
    }

    private fun decodeQueueVideo(item: JSONObject): VideoItem {
        if (item.optString("Kind", "video") != "playlist") return decodeVideo(item)
        val id = item.requiredString("Id")
        val title = item.requiredString("Title")
        val video = VideoItem(
            id = id,
            title = title,
            channel = item.optString("Channel", ""),
            kind = "playlist",
            thumbnailUrl = "",
            url = "https://www.youtube.com/playlist?list=$id",
        )
        validateQueueVideo(video)
        return video
    }

    private fun validateQueueVideo(video: VideoItem) {
        if (video.kind == "playlist") {
            require(QUEUE_PLAYLIST_ID.matches(video.id)) { "Backup contains an invalid queued playlist." }
            require(video.title.isNotBlank() && video.title.length <= MAX_VIDEO_TITLE_LENGTH)
        } else {
            validateVideo(video)
        }
    }

    private fun videoObject(video: VideoItem) = JSONObject()
        .put("Id", video.id)
        .put("Title", video.title)
        .put("Channel", video.channel)
        .put("ChannelId", video.channelId)
        .put("Published", video.published)
        .put("Duration", video.duration)
        .put("Kind", video.kind)
        .put("PublishedAtMs", video.publishedAtMs)

    private fun decodeVideo(item: JSONObject): VideoItem {
        val id = item.requiredString("Id")
        val title = item.requiredString("Title")
        val channel = item.optString("Channel", "")
        val channelId = item.optString("ChannelId", "")
        val published = item.optString("Published", "")
        val duration = item.optString("Duration", "—")
        val kind = item.optString("Kind", "video")
        val publishedAt = item.optLong("PublishedAtMs", 0L)
        val video = VideoItem(
            id = id,
            title = title,
            channel = channel,
            channelId = channelId,
            published = published,
            duration = duration,
            kind = kind,
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            url = "https://www.youtube.com/watch?v=$id",
            publishedAtMs = publishedAt,
        )
        validateVideo(video)
        return video
    }

    private fun JSONObject.requiredArray(name: String): JSONArray = get(name) as? JSONArray
        ?: throw IllegalArgumentException("Backup field $name is missing or invalid.")

    private fun JSONObject.requiredString(name: String): String = get(name) as? String
        ?: throw IllegalArgumentException("Backup field $name is missing or invalid.")

    private fun JSONObject.requiredLong(name: String): Long {
        val value = get(name)
        val number = value as? Number ?: throw IllegalArgumentException("Backup field $name is missing or invalid.")
        val long = number.toLong()
        require(number.toDouble() == long.toDouble()) { "Backup field $name is invalid." }
        return long
    }

    private const val FORMAT = "LowTube.LocalLibrary"
    private const val SCHEMA_VERSION = 1
    private const val MAX_JSON_CHARS = 16 * 1024 * 1024
    private const val MAX_TOTAL_VIDEO_ROWS = 20_000
    private const val MAX_CHANNEL_TITLE_LENGTH = 300
    private const val MAX_VIDEO_TITLE_LENGTH = 1000
    private const val MAX_CHANNEL_ID_LENGTH = 128
    private const val MAX_PUBLISHED_LENGTH = 100
    private const val MAX_DURATION_LENGTH = 30
    private const val MAX_CATEGORY_LENGTH = 64
    private const val MAX_PLAYLIST_NAME_LENGTH = 100
    private const val MAX_QUEUE_CONTINUATION_LENGTH = 16 * 1024
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val QUEUE_PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{1,256}$")
}
