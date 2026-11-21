package com.lowtube.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lowtube.android.model.DownloadJob
import com.lowtube.android.model.DownloadStatus

/** Durable metadata only; signed GoogleVideo URLs are deliberately never persisted. */
internal class DownloadJobStore(context: Context, databaseName: String = DEFAULT_DB_NAME) : SQLiteOpenHelper(context, databaseName, null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, video_json TEXT NOT NULL, format_key TEXT NOT NULL, audio_only INTEGER NOT NULL, status INTEGER NOT NULL, reason TEXT NOT NULL DEFAULT '', downloaded INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, local_uri TEXT NOT NULL DEFAULT '', partial_path TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL, user_agent TEXT NOT NULL, created_at INTEGER NOT NULL, max_bytes_per_attempt INTEGER NOT NULL DEFAULT 0, transcode_mp3 INTEGER NOT NULL DEFAULT 0, target_audio_bitrate_kbps INTEGER NOT NULL DEFAULT 192)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE jobs ADD COLUMN max_bytes_per_attempt INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN transcode_mp3 INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE jobs ADD COLUMN target_audio_bitrate_kbps INTEGER NOT NULL DEFAULT 192")
        }
    }

    @Synchronized fun insert(videoJson: String, formatKey: String, audioOnly: Boolean, fileName: String, mime: String, userAgent: String, partialPath: String, maxBytesPerAttempt: Long = 0L, transcodeMp3: Boolean = false, targetAudioBitrateKbps: Int = 192): Long {
        val values = ContentValues().apply {
            put("video_json", videoJson); put("format_key", formatKey); put("audio_only", if (audioOnly) 1 else 0)
            put("status", DownloadStatus.PENDING); put("partial_path", partialPath); put("file_name", fileName)
            put("mime_type", mime); put("user_agent", userAgent); put("created_at", System.currentTimeMillis())
            put("max_bytes_per_attempt", maxBytesPerAttempt)
            put("transcode_mp3", if (transcodeMp3) 1 else 0); put("target_audio_bitrate_kbps", targetAudioBitrateKbps)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Seed the first ID above the legacy DownloadManager range. SQLite allocates the rest.
            val seeded = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='jobs'", null).use {
                it.moveToFirst() && it.getLong(0) >= ID_BASE
            }
            if (!seeded) values.put("id", ID_BASE)
            val id = db.insertOrThrow("jobs", null, values)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun get(id: Long): DownloadJob? = readableDatabase.query("jobs", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
        if (c.moveToFirst()) readJob(c) else null
    }

    private fun readJob(c: Cursor): DownloadJob =
        DownloadJob(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("video_json")), c.getString(c.getColumnIndexOrThrow("format_key")), c.getInt(c.getColumnIndexOrThrow("audio_only")) != 0, c.getInt(c.getColumnIndexOrThrow("status")), c.getString(c.getColumnIndexOrThrow("reason")), c.getLong(c.getColumnIndexOrThrow("downloaded")), c.getLong(c.getColumnIndexOrThrow("total")), c.getString(c.getColumnIndexOrThrow("local_uri")), c.getString(c.getColumnIndexOrThrow("partial_path")), c.getString(c.getColumnIndexOrThrow("file_name")), c.getString(c.getColumnIndexOrThrow("mime_type")), c.getString(c.getColumnIndexOrThrow("user_agent")), c.getLong(c.getColumnIndexOrThrow("created_at")), c.getLong(c.getColumnIndexOrThrow("max_bytes_per_attempt")), c.getInt(c.getColumnIndexOrThrow("transcode_mp3")) != 0, c.getInt(c.getColumnIndexOrThrow("target_audio_bitrate_kbps")))

    @Synchronized fun all(): List<DownloadJob> = readableDatabase.query("jobs", null, null, null, null, null, "created_at DESC").use { c ->
        buildList { while (c.moveToNext()) add(readJob(c)) }
    }
    @Synchronized fun statusCounts(): Map<Int, Long> = readableDatabase.rawQuery(
        "SELECT status, COUNT(*) FROM jobs GROUP BY status",
        null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getInt(0), cursor.getLong(1))
        }
    }
    @Synchronized fun pending(): DownloadJob? = readableDatabase.query("jobs", null, "status IN (?,?)", arrayOf(DownloadStatus.PENDING.toString(), DownloadStatus.RUNNING.toString()), null, null, "created_at ASC", "1").use { c ->
        if (c.moveToFirst()) readJob(c) else null
    }
    @Synchronized fun update(id: Long, status: Int, reason: String = "", downloaded: Long? = null, total: Long? = null, uri: String? = null) {
        val values = ContentValues().apply { put("status", status); put("reason", reason); downloaded?.let { put("downloaded", it) }; total?.let { put("total", it) }; uri?.let { put("local_uri", it) } }
        writableDatabase.update("jobs", values, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun remove(id: Long) { writableDatabase.delete("jobs", "id=?", arrayOf(id.toString())) }
    @Synchronized fun requeueRetryable(id: Long): Boolean {
        val values = ContentValues().apply {
            put("status", DownloadStatus.PENDING)
            put("reason", "")
        }
        return writableDatabase.update(
            "jobs", values, "id=? AND status IN (?,?,?)",
            arrayOf(id.toString(), DownloadStatus.FAILED.toString(), DownloadStatus.CANCELLED.toString(), DownloadStatus.PAUSED.toString()),
        ) == 1
    }
    @Synchronized fun requeueInterrupted() { writableDatabase.execSQL("UPDATE jobs SET status=?, reason='' WHERE status=?", arrayOf(DownloadStatus.PENDING, DownloadStatus.RUNNING)) }

    private companion object {
        const val ID_BASE = 1_000_000_000_000L
        const val DEFAULT_DB_NAME = "lowtube_download_queue.db"
    }
}
