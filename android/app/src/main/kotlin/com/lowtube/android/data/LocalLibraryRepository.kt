package com.lowtube.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lowtube.android.model.LocalPlaylist
import com.lowtube.android.model.LocalAudioTrack
import com.lowtube.android.model.QueueEntry
import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.WatchHistoryEntry
import com.lowtube.android.model.YouTubeSubscription
import java.util.UUID

/** Local-only library. It never reads Android accounts, browser cookies, or private app data. */
class LocalLibraryRepository(context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        pruneExpiredYouTubeSubscriptions(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE subscriptions (channel_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, added_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL COLLATE NOCASE UNIQUE, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE playlist_items (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            video_id TEXT NOT NULL,
            title TEXT NOT NULL,
            channel TEXT NOT NULL,
            channel_id TEXT NOT NULL,
            published TEXT NOT NULL,
            duration TEXT NOT NULL,
            kind TEXT NOT NULL,
            thumbnail_url TEXT NOT NULL,
            url TEXT NOT NULL,
            position INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX playlist_items_order ON playlist_items(playlist_id, position, row_id)")
        createQueueTable(db, includeContinuation = true)
        createWatchHistoryTable(db)
        createSubscriptionFeedTables(db)
        createLocalAudioTable(db)
        createYouTubeSubscriptionTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createQueueTable(db)
        if (oldVersion < 3) createWatchHistoryTable(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE queue_entries ADD COLUMN continuation TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 5) createSubscriptionFeedTables(db)
        if (oldVersion < 6) createLocalAudioTable(db)
        if (oldVersion < 7) createYouTubeSubscriptionTable(db)
    }

    fun localAudioTracks(): List<LocalAudioTrack> = readableDatabase.rawQuery(
        "SELECT content_uri, title, mime_type, added_at FROM local_audio_tracks ORDER BY title COLLATE NOCASE, added_at DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(LocalAudioTrack(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
    }

    fun saveLocalAudioTrack(uri: String, title: String, mimeType: String): Boolean {
        require(uri.startsWith("content://") && uri.length <= MAX_LOCAL_AUDIO_URI_LENGTH) { "Choose an audio file from the Android file picker." }
        val cleanTitle = title.trim().take(MAX_LOCAL_AUDIO_TITLE_LENGTH)
        require(cleanTitle.isNotEmpty()) { "The selected audio file has no name." }
        val cleanMimeType = mimeType.trim().lowercase().take(128)
        require(cleanMimeType.startsWith("audio/")) { "Choose an audio file." }
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM local_audio_tracks WHERE content_uri=?", arrayOf(uri)).use { it.moveToFirst() }
        if (!exists) {
            val count = db.rawQuery("SELECT COUNT(*) FROM local_audio_tracks", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            require(count < MAX_LOCAL_AUDIO_TRACKS) { "You can add up to $MAX_LOCAL_AUDIO_TRACKS local audio files." }
        }
        val values = ContentValues().apply {
            put("content_uri", uri)
            put("title", cleanTitle)
            put("mime_type", cleanMimeType)
            put("added_at", System.currentTimeMillis())
        }
        val row = db.insertWithOnConflict("local_audio_tracks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return row != -1L
    }

    fun removeLocalAudioTrack(uri: String): Boolean = writableDatabase.delete("local_audio_tracks", "content_uri=?", arrayOf(uri)) > 0

    private fun createLocalAudioTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_audio_tracks (content_uri TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, mime_type TEXT NOT NULL, added_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX local_audio_tracks_order ON local_audio_tracks(title COLLATE NOCASE, added_at DESC)")
    }

    fun localChannels(): List<SavedChannel> = readableDatabase.rawQuery(
        "SELECT channel_id, title, url FROM subscriptions ORDER BY title COLLATE NOCASE", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(SavedChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
        }
    }

    fun channels(accountEmail: String = ""): List<SavedChannel> {
        val local = localChannels()
        val owner = accountEmail.trim().lowercase()
        if (owner.isBlank()) return local
        val localIds = local.mapTo(HashSet()) { it.id }
        val account = readableDatabase.rawQuery(
            "SELECT channel_id, title, url FROM youtube_account_subscriptions WHERE owner_email=? ORDER BY title COLLATE NOCASE", arrayOf(owner),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (id !in localIds) add(SavedChannel(id, cursor.getString(1), cursor.getString(2), true))
                }
            }
        }
        return (local + account).sortedBy { it.title.lowercase() }
    }

    fun replaceYouTubeAccountSubscriptions(accountEmail: String, subscriptions: List<YouTubeSubscription>, syncedAt: Long = System.currentTimeMillis()) {
        val owner = accountEmail.trim().lowercase()
        require(owner.isNotBlank() && owner.length <= MAX_ACCOUNT_EMAIL_LENGTH)
        require(subscriptions.size <= MAX_YOUTUBE_SUBSCRIPTIONS)
        require(subscriptions.map { it.channelId }.distinct().size == subscriptions.size)
        require(subscriptions.all { CHANNEL_ID.matches(it.channelId) && it.title.isNotBlank() && it.title.length <= 250 })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("youtube_account_subscriptions", null, null)
            subscriptions.forEach { subscription ->
                val values = ContentValues().apply {
                    put("owner_email", owner)
                    put("channel_id", subscription.channelId)
                    put("title", subscription.title)
                    put("url", "https://www.youtube.com/channel/${subscription.channelId}")
                    put("synced_at", syncedAt)
                }
                check(db.insert("youtube_account_subscriptions", null, values) != -1L) { "Could not save YouTube subscriptions." }
            }
            db.execSQL("DELETE FROM channel_feed_items WHERE channel_id NOT IN (SELECT channel_id FROM subscriptions) AND channel_id NOT IN (SELECT channel_id FROM youtube_account_subscriptions)")
            db.execSQL("DELETE FROM channel_feed_state WHERE channel_id NOT IN (SELECT channel_id FROM subscriptions) AND channel_id NOT IN (SELECT channel_id FROM youtube_account_subscriptions)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearYouTubeAccountSubscriptions() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM channel_feed_items WHERE channel_id IN (SELECT channel_id FROM youtube_account_subscriptions WHERE channel_id NOT IN (SELECT channel_id FROM subscriptions))")
            db.execSQL("DELETE FROM channel_feed_state WHERE channel_id IN (SELECT channel_id FROM youtube_account_subscriptions WHERE channel_id NOT IN (SELECT channel_id FROM subscriptions))")
            db.delete("youtube_account_subscriptions", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createYouTubeSubscriptionTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE youtube_account_subscriptions (channel_id TEXT PRIMARY KEY NOT NULL, owner_email TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, synced_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX youtube_account_subscriptions_expiry ON youtube_account_subscriptions(synced_at)")
    }

    private fun pruneExpiredYouTubeSubscriptions(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - YOUTUBE_SUBSCRIPTION_TTL_MS
        db.execSQL("DELETE FROM channel_feed_items WHERE channel_id IN (SELECT channel_id FROM youtube_account_subscriptions WHERE synced_at < ? AND channel_id NOT IN (SELECT channel_id FROM subscriptions))", arrayOf<Any>(cutoff))
        db.execSQL("DELETE FROM channel_feed_state WHERE channel_id IN (SELECT channel_id FROM youtube_account_subscriptions WHERE synced_at < ? AND channel_id NOT IN (SELECT channel_id FROM subscriptions))", arrayOf<Any>(cutoff))
        db.delete("youtube_account_subscriptions", "synced_at < ?", arrayOf(cutoff.toString()))
    }

    fun isFollowing(channelId: String): Boolean {
        if (channelId.isBlank()) return false
        return readableDatabase.rawQuery("SELECT 1 FROM subscriptions WHERE channel_id=?", arrayOf(channelId)).use { it.moveToFirst() }
    }

    fun follow(channelId: String, title: String, url: String) {
        require(CHANNEL_ID.matches(channelId)) { "This item does not include a valid channel ID." }
        if (!isFollowing(channelId)) {
            val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM subscriptions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            require(count < MAX_CHANNELS) { "You can follow up to $MAX_CHANNELS local channels." }
        }
        val values = ContentValues().apply {
            put("channel_id", channelId)
            put("title", title.take(250))
            put("url", url.take(2048))
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("subscriptions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unfollow(channelId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("subscriptions", "channel_id=?", arrayOf(channelId))
            db.delete("channel_feed_items", "channel_id=?", arrayOf(channelId))
            db.delete("channel_feed_state", "channel_id=?", arrayOf(channelId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun channelsForFeedRefresh(channels: List<SavedChannel>, limit: Int): List<SavedChannel> {
        if (channels.isEmpty() || limit <= 0) return emptyList()
        val lastAttempt = mutableMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT channel_id, last_attempt_ms FROM channel_feed_state", null).use { cursor ->
            while (cursor.moveToNext()) lastAttempt[cursor.getString(0)] = cursor.getLong(1)
        }
        return channels.sortedWith(compareBy<SavedChannel> { lastAttempt[it.id] ?: 0L }.thenBy { it.id }).take(limit)
    }

    fun unattemptedFeedChannelCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM subscriptions s LEFT JOIN channel_feed_state f ON f.channel_id=s.channel_id WHERE f.channel_id IS NULL",
        null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun replaceChannelFeed(channelId: String, videos: List<VideoItem>, updatedAt: Long) {
        require(CHANNEL_ID.matches(channelId))
        require(videos.size <= MAX_FEED_ITEMS_PER_CHANNEL && videos.all { VIDEO_ID.matches(it.id) && it.channelId == channelId })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channel_feed_items", "channel_id=?", arrayOf(channelId))
            videos.forEach { video ->
                val values = videoValues(video).apply {
                    put("channel_id", channelId)
                    put("published_at_ms", video.publishedAtMs)
                    put("updated_at", updatedAt)
                }
                check(db.insert("channel_feed_items", null, values) != -1L) { "Could not cache channel uploads." }
            }
            val state = ContentValues().apply { put("channel_id", channelId); put("last_attempt_ms", updatedAt) }
            db.insertWithOnConflict("channel_feed_state", null, state, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM channel_feed_items WHERE row_id NOT IN (SELECT row_id FROM channel_feed_items ORDER BY published_at_ms DESC, video_id LIMIT ?)", arrayOf<Any>(MAX_SUBSCRIPTION_FEED_ITEMS))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun markChannelFeedAttempt(channelId: String, attemptedAt: Long) {
        val values = ContentValues().apply { put("channel_id", channelId); put("last_attempt_ms", attemptedAt) }
        writableDatabase.insertWithOnConflict("channel_feed_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun subscriptionFeed(): List<VideoItem> = readableDatabase.rawQuery(
        "SELECT video_id, title, channel, channel_id, published, duration, kind, thumbnail_url, url, published_at_ms FROM channel_feed_items ORDER BY published_at_ms DESC, video_id LIMIT ?",
        arrayOf(MAX_SUBSCRIPTION_FEED_ITEMS.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(VideoItem(
                id = cursor.getString(0), title = cursor.getString(1), channel = cursor.getString(2), channelId = cursor.getString(3),
                published = cursor.getString(4), duration = cursor.getString(5), kind = cursor.getString(6),
                thumbnailUrl = cursor.getString(7), url = cursor.getString(8), publishedAtMs = cursor.getLong(9),
            ))
        }
    }

    private fun createSubscriptionFeedTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE channel_feed_items (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            video_id TEXT NOT NULL,
            title TEXT NOT NULL,
            channel TEXT NOT NULL,
            channel_id TEXT NOT NULL,
            published TEXT NOT NULL,
            duration TEXT NOT NULL,
            kind TEXT NOT NULL,
            thumbnail_url TEXT NOT NULL,
            url TEXT NOT NULL,
            published_at_ms INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(channel_id, video_id)
        )""")
        db.execSQL("CREATE INDEX channel_feed_recent ON channel_feed_items(published_at_ms DESC, video_id)")
        db.execSQL("CREATE TABLE channel_feed_state (channel_id TEXT PRIMARY KEY NOT NULL, last_attempt_ms INTEGER NOT NULL)")
    }

    fun playlists(): List<LocalPlaylist> = readableDatabase.rawQuery(
        "SELECT id, name FROM playlists ORDER BY created_at, name COLLATE NOCASE", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(LocalPlaylist(cursor.getString(0), cursor.getString(1)))
        }
    }

    fun createPlaylist(name: String): String {
        val clean = cleanName(name)
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM playlists", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_PLAYLISTS) { "You can create up to $MAX_PLAYLISTS local playlists." }
        val id = UUID.randomUUID().toString().replace("-", "")
        val values = ContentValues().apply {
            put("id", id)
            put("name", clean)
            put("created_at", System.currentTimeMillis())
        }
        require(db.insert("playlists", null, values) != -1L) { "A playlist with this name already exists." }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val values = ContentValues().apply { put("name", cleanName(name)) }
        require(writableDatabase.update("playlists", values, "id=?", arrayOf(id)) == 1) { "The local playlist no longer exists." }
    }

    fun deletePlaylist(id: String) {
        writableDatabase.delete("playlists", "id=?", arrayOf(id))
    }

    fun saveToWatchLater(video: VideoItem, displayName: String = WATCH_LATER) {
        val cleanDisplayName = cleanName(displayName)
        val db = writableDatabase
        db.beginTransaction()
        try {
            var playlistId = db.rawQuery("SELECT id FROM playlists WHERE id=?", arrayOf(WATCH_LATER_ID)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (playlistId == null) {
                val count = db.rawQuery("SELECT COUNT(*) FROM playlists", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                require(count < MAX_PLAYLISTS) { "You can create up to $MAX_PLAYLISTS local playlists." }
                playlistId = WATCH_LATER_ID
                val values = ContentValues().apply {
                    put("id", playlistId)
                    put("name", cleanDisplayName)
                    put("created_at", System.currentTimeMillis())
                }
                check(db.insert("playlists", null, values) != -1L) { "Could not create Watch later." }
            } else {
                db.execSQL("UPDATE playlists SET name=? WHERE id=?", arrayOf<Any>(cleanDisplayName, playlistId))
            }
            addVideo(db, playlistId, video)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addVideo(playlistId: String, video: VideoItem) = addVideo(writableDatabase, playlistId, video)

    private fun addVideo(db: SQLiteDatabase, playlistId: String, video: VideoItem) {
        require(video.kind != "playlist" && video.id.isNotBlank() && video.url.isNotBlank()) { "Only videos can be added to a local playlist." }
        val count = db.rawQuery("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=?", arrayOf(playlistId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_ITEMS_PER_PLAYLIST) { "A local playlist can contain up to $MAX_ITEMS_PER_PLAYLIST videos." }
        val nextPosition = db.rawQuery("SELECT COALESCE(MAX(position), -1)+1 FROM playlist_items WHERE playlist_id=?", arrayOf(playlistId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val values = videoValues(video).apply { put("playlist_id", playlistId); put("position", nextPosition) }
        require(db.insert("playlist_items", null, values) != -1L) { "Could not save this video." }
    }

    private fun videoValues(video: VideoItem) = ContentValues().apply {
        put("video_id", video.id)
        put("title", video.title.take(1000))
        put("channel", video.channel.take(300))
        put("channel_id", video.channelId)
        put("published", video.published.take(100))
        put("duration", video.duration.take(30))
        put("kind", video.kind.take(30))
        put("thumbnail_url", video.thumbnailUrl.take(2000))
        put("url", video.url.take(3000))
    }

    private fun createQueueTable(db: SQLiteDatabase, includeContinuation: Boolean = false) {
        db.execSQL("""CREATE TABLE queue_entries (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            video_id TEXT NOT NULL,
            title TEXT NOT NULL,
            channel TEXT NOT NULL,
            channel_id TEXT NOT NULL,
            published TEXT NOT NULL,
            duration TEXT NOT NULL,
            kind TEXT NOT NULL,
            thumbnail_url TEXT NOT NULL,
            url TEXT NOT NULL,
            position INTEGER NOT NULL${if (includeContinuation) ", continuation TEXT NOT NULL DEFAULT ''" else ""}
        )""")
        db.execSQL("CREATE INDEX queue_entries_order ON queue_entries(position, row_id)")
    }

    private fun createWatchHistoryTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE watch_history (
            video_id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            channel TEXT NOT NULL,
            channel_id TEXT NOT NULL,
            published TEXT NOT NULL,
            duration TEXT NOT NULL,
            kind TEXT NOT NULL,
            thumbnail_url TEXT NOT NULL,
            url TEXT NOT NULL,
            position_ms INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            complete INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX watch_history_recent ON watch_history(updated_at DESC)")
    }

    fun videos(playlistId: String) = readableDatabase.rawQuery(
        "SELECT video_id, title, channel, channel_id, published, duration, kind, thumbnail_url, url FROM playlist_items WHERE playlist_id=? ORDER BY position, row_id",
        arrayOf(playlistId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                VideoItem(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    channel = cursor.getString(2),
                    channelId = cursor.getString(3),
                    published = cursor.getString(4),
                    duration = cursor.getString(5),
                    kind = cursor.getString(6),
                    thumbnailUrl = cursor.getString(7),
                    url = cursor.getString(8),
                ),
            )
        }
    }

    fun removeVideo(playlistId: String, rowIndex: Int) {
        require(rowIndex >= 0)
        writableDatabase.delete(
            "playlist_items",
            "row_id=(SELECT row_id FROM playlist_items WHERE playlist_id=? ORDER BY position, row_id LIMIT 1 OFFSET ?)",
            arrayOf(playlistId, rowIndex.toString()),
        )
    }

    fun localizeWatchLater(displayName: String) {
        writableDatabase.execSQL("UPDATE playlists SET name=? WHERE id=?", arrayOf<Any>(cleanName(displayName), WATCH_LATER_ID))
    }

    fun queue(): List<QueueEntry> = readableDatabase.rawQuery(
        "SELECT row_id, video_id, title, channel, channel_id, published, duration, kind, thumbnail_url, url, continuation FROM queue_entries ORDER BY position, row_id",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                QueueEntry(
                    rowId = cursor.getLong(0),
                    video = VideoItem(
                        id = cursor.getString(1),
                        title = cursor.getString(2),
                        channel = cursor.getString(3),
                        channelId = cursor.getString(4),
                        published = cursor.getString(5),
                        duration = cursor.getString(6),
                        kind = cursor.getString(7),
                        thumbnailUrl = cursor.getString(8),
                        url = cursor.getString(9),
                    ),
                    continuation = cursor.getString(10),
                ),
            )
        }
    }

    fun enqueue(video: VideoItem) {
        require(video.id.isNotBlank() && video.url.isNotBlank()) { "This item cannot be queued." }
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM queue_entries", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        require(count < MAX_QUEUE_ITEMS) { "The watch queue is full." }
        val position = db.rawQuery("SELECT COALESCE(MAX(position), -1)+1 FROM queue_entries", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val values = videoValues(video).apply { put("position", position) }
        require(db.insert("queue_entries", null, values) != -1L) { "Could not add this item to the queue." }
    }

    /** Adds a playlist snapshot atomically, preserving its order and the queue size limit. */
    fun enqueueAll(videos: List<VideoItem>) {
        if (videos.isEmpty()) return
        require(videos.all { it.id.isNotBlank() && it.url.isNotBlank() && it.kind != "playlist" }) {
            "Only playable videos can be added to the queue."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT COUNT(*) FROM queue_entries", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            require(count + videos.size <= MAX_QUEUE_ITEMS) { "The watch queue is full." }
            var position = db.rawQuery("SELECT COALESCE(MAX(position), -1)+1 FROM queue_entries", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            videos.forEach { video ->
                val values = videoValues(video).apply { put("position", position++) }
                require(db.insert("queue_entries", null, values) != -1L) { "Could not add playlist videos to the queue." }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun expandQueuedPlaylist(rowId: Long, videos: List<VideoItem>, nextContinuation: String?) {
        require(videos.size <= MAX_PAGE_ITEMS) { "The playlist page is too large." }
        require(videos.none { it.kind == "playlist" || !VIDEO_ID.matches(it.id) }) { "The playlist page contains an invalid item." }
        if (!nextContinuation.isNullOrBlank()) require(nextContinuation.length <= MAX_QUEUE_CONTINUATION_LENGTH) { "The playlist continuation is too large." }
        val rows = queue().toMutableList()
        val index = rows.indexOfFirst { it.rowId == rowId }
        require(index >= 0 && rows[index].video.kind == "playlist") { "This playlist is no longer in the queue." }
        val original = rows[index]
        val keepMarker = !nextContinuation.isNullOrBlank()
        val newCount = rows.size + videos.size - if (keepMarker) 0 else 1
        require(newCount <= MAX_QUEUE_ITEMS) { "The watch queue is full." }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val addedEntries = videos.map { video ->
                val values = videoValues(video).apply { put("position", 0); put("continuation", "") }
                val insertedId = db.insert("queue_entries", null, values)
                require(insertedId != -1L) { "Could not add playlist videos to the queue." }
                QueueEntry(insertedId, video)
            }
            if (keepMarker) {
                val updated = ContentValues().apply { put("continuation", nextContinuation) }
                require(db.update("queue_entries", updated, "row_id=?", arrayOf(rowId.toString())) == 1) {
                    "Could not save the next playlist page."
                }
                rows[index] = original.copy(continuation = nextContinuation.orEmpty())
                rows.addAll(index, addedEntries)
            } else {
                require(db.delete("queue_entries", "row_id=?", arrayOf(rowId.toString())) == 1) {
                    "Could not remove the expanded playlist marker."
                }
                rows.removeAt(index)
                rows.addAll(index, addedEntries)
            }
            rows.forEachIndexed { position, entry ->
                db.execSQL("UPDATE queue_entries SET position=? WHERE row_id=?", arrayOf<Any>(position, entry.rowId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeQueueEntry(rowId: Long) {
        writableDatabase.delete("queue_entries", "row_id=?", arrayOf(rowId.toString()))
    }

    fun clearQueue() {
        writableDatabase.delete("queue_entries", null, null)
    }

    fun moveQueueEntry(rowId: Long, direction: Int) {
        require(direction == -1 || direction == 1)
        val rows = queue().map { it.rowId }.toMutableList()
        val from = rows.indexOf(rowId)
        val to = from + direction
        if (from !in rows.indices || to !in rows.indices) return
        val id = rows.removeAt(from)
        rows.add(to, id)
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEachIndexed { position, entryId ->
                db.execSQL("UPDATE queue_entries SET position=? WHERE row_id=?", arrayOf<Any>(position, entryId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordWatchProgress(video: VideoItem, positionMs: Long, durationMs: Long, complete: Boolean = false) {
        if (!VIDEO_ID.matches(video.id) || video.kind == "live" || video.liveStatus in setOf("is_live", "is_upcoming")) return
        if (positionMs < 0 || durationMs < 0) return
        if (positionMs == 0L && !complete) return
        val db = writableDatabase
        val previousDuration = db.rawQuery("SELECT duration_ms FROM watch_history WHERE video_id=?", arrayOf(video.id)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        val effectiveDuration = durationMs.takeIf { it > 0 } ?: previousDuration
        val savedPosition = if (complete && effectiveDuration > 0) effectiveDuration else positionMs
        val values = videoValues(video).apply {
            put("position_ms", savedPosition)
            put("duration_ms", effectiveDuration)
            put("complete", if (complete) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("watch_history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM watch_history WHERE video_id IN (SELECT video_id FROM watch_history ORDER BY updated_at DESC LIMIT -1 OFFSET ?)", arrayOf<Any>(MAX_HISTORY_ITEMS))
    }

    fun watchHistory(): List<WatchHistoryEntry> = readableDatabase.rawQuery(
        "SELECT video_id, title, channel, channel_id, published, duration, kind, thumbnail_url, url, position_ms, duration_ms, complete, updated_at FROM watch_history ORDER BY updated_at DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                WatchHistoryEntry(
                    video = VideoItem(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        channel = cursor.getString(2),
                        channelId = cursor.getString(3),
                        published = cursor.getString(4),
                        duration = cursor.getString(5),
                        kind = cursor.getString(6),
                        thumbnailUrl = cursor.getString(7),
                        url = cursor.getString(8),
                    ),
                    positionMs = cursor.getLong(9),
                    durationMs = cursor.getLong(10),
                    complete = cursor.getInt(11) != 0,
                    updatedAt = cursor.getLong(12),
                ),
            )
        }
    }

    fun previousWatchedVideo(currentVideoId: String): VideoItem? =
        watchHistory().firstOrNull { it.video.id != currentVideoId }?.video

    fun resumePositionMs(videoId: String): Long {
        if (!VIDEO_ID.matches(videoId)) return 0
        return readableDatabase.rawQuery("SELECT position_ms, duration_ms, complete FROM watch_history WHERE video_id=?", arrayOf(videoId)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(2) != 0) return@use 0L
            val position = cursor.getLong(0)
            val duration = cursor.getLong(1)
            if (position < RESUME_THRESHOLD_MS) 0L
            else if (duration <= 0 || position < duration - minOf(RESUME_END_GUARD_MS, (duration * 0.02).toLong())) position
            else 0L
        }
    }

    fun clearWatchedProgress(videoId: String) {
        writableDatabase.delete("watch_history", "video_id=?", arrayOf(videoId))
    }

    fun clearWatchHistory() {
        writableDatabase.delete("watch_history", null, null)
    }

    fun moveVideo(playlistId: String, fromIndex: Int, toIndex: Int) {
        val db = writableDatabase
        val rows = ArrayList<Long>()
        db.rawQuery("SELECT row_id FROM playlist_items WHERE playlist_id=? ORDER BY position, row_id", arrayOf(playlistId)).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getLong(0)
        }
        if (fromIndex !in rows.indices || toIndex !in rows.indices || fromIndex == toIndex) return
        val rowId = rows.removeAt(fromIndex)
        rows.add(toIndex, rowId)
        db.beginTransaction()
        try {
            rows.forEachIndexed { position, id ->
                db.execSQL("UPDATE playlist_items SET position=? WHERE row_id=?", arrayOf<Any>(position, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cleanName(name: String): String = name.trim().take(100).also {
        require(it.isNotEmpty()) { "Playlist name cannot be empty." }
        require(it.any { character -> !character.isWhitespace() }) { "Playlist name cannot be empty." }
    }

    companion object {
        const val WATCH_LATER = "Watch later"
        const val MAX_PLAYLISTS = 200
        const val MAX_ITEMS_PER_PLAYLIST = 1000
        const val MAX_QUEUE_ITEMS = 5000
        const val MAX_HISTORY_ITEMS = 5000
        const val MAX_LOCAL_AUDIO_TRACKS = 1000
        const val MAX_CHANNELS = 100
        const val MAX_YOUTUBE_SUBSCRIPTIONS = 5000
        private const val MAX_ACCOUNT_EMAIL_LENGTH = 320
        private const val MAX_PAGE_ITEMS = 100
        private const val MAX_QUEUE_CONTINUATION_LENGTH = 16 * 1024
        private const val DATABASE_NAME = "lowtube-library.db"
        private const val DATABASE_VERSION = 7
        private const val YOUTUBE_SUBSCRIPTION_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val WATCH_LATER_ID = "__lowtube_watch_later__"
        private const val RESUME_THRESHOLD_MS = 5000L
        private const val RESUME_END_GUARD_MS = 10_000L
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private const val MAX_FEED_ITEMS_PER_CHANNEL = 30
        private const val MAX_SUBSCRIPTION_FEED_ITEMS = 3000
        private const val MAX_LOCAL_AUDIO_URI_LENGTH = 4096
        private const val MAX_LOCAL_AUDIO_TITLE_LENGTH = 512
    }
}
