package com.lowtube.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lowtube.android.model.DownloadJob
import com.lowtube.android.model.DownloadStatus

/** Durable metadata only; signed GoogleVideo URLs are deliberately never persisted. */
internal class DownloadJobStore(context: Context, databaseName: String = DEFAULT_DB_NAME) : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, video_json TEXT NOT NULL, format_key TEXT NOT NULL, audio_only INTEGER NOT NULL, status INTEGER NOT NULL, reason TEXT NOT NULL DEFAULT '', downloaded INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, local_uri TEXT NOT NULL DEFAULT '', partial_path TEXT NOT NULL, file_name TEXT NOT NULL, mime_type TEXT NOT NULL, user_agent TEXT NOT NULL, created_at INTEGER NOT NULL, max_bytes_per_attempt INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE jobs ADD COLUMN max_bytes_per_attempt INTEGER NOT NULL DEFAULT 0")
    }

    @Synchronized fun insert(videoJson: String, formatKey: String, audioOnly: Boolean, fileName: String, mime: String, userAgent: String, partialPath: String, maxBytesPerAttempt: Long = 0L): Long {
        val id = (readableDatabase.rawQuery("SELECT MAX(id) FROM jobs", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }).let { if (it < ID_BASE) ID_BASE else it + 1L }
        val values = ContentValues().apply {
            put("id", id)
            put("video_json", videoJson); put("format_key", formatKey); put("audio_only", if (audioOnly) 1 else 0)
            put("status", DownloadStatus.PENDING); put("partial_path", partialPath); put("file_name", fileName)
            put("mime_type", mime); put("user_agent", userAgent); put("created_at", System.currentTimeMillis())
            put("max_bytes_per_attempt", maxBytesPerAttempt)
        }
        writableDatabase.insertOrThrow("jobs", null, values)
        return id
    }

    @Synchronized fun get(id: Long): DownloadJob? = readableDatabase.query("jobs", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
        if (!c.moveToFirst()) return null
        DownloadJob(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("video_json")), c.getString(c.getColumnIndexOrThrow("format_key")), c.getInt(c.getColumnIndexOrThrow("audio_only")) != 0, c.getInt(c.getColumnIndexOrThrow("status")), c.getString(c.getColumnIndexOrThrow("reason")), c.getLong(c.getColumnIndexOrThrow("downloaded")), c.getLong(c.getColumnIndexOrThrow("total")), c.getString(c.getColumnIndexOrThrow("local_uri")), c.getString(c.getColumnIndexOrThrow("partial_path")), c.getString(c.getColumnIndexOrThrow("file_name")), c.getString(c.getColumnIndexOrThrow("mime_type")), c.getString(c.getColumnIndexOrThrow("user_agent")), c.getLong(c.getColumnIndexOrThrow("created_at")), c.getLong(c.getColumnIndexOrThrow("max_bytes_per_attempt")))
    }

    @Synchronized fun all(): List<DownloadJob> = readableDatabase.query("jobs", null, null, null, null, null, "created_at DESC").use { c ->
        buildList { while (c.moveToNext()) get(c.getLong(c.getColumnIndexOrThrow("id")))?.let(::add) }
    }
    @Synchronized fun pending(): DownloadJob? = readableDatabase.query("jobs", null, "status IN (?,?)", arrayOf(DownloadStatus.PENDING.toString(), DownloadStatus.RUNNING.toString()), null, null, "created_at ASC", "1").use { c ->
        if (c.moveToFirst()) get(c.getLong(c.getColumnIndexOrThrow("id"))) else null
    }
    @Synchronized fun update(id: Long, status: Int, reason: String = "", downloaded: Long? = null, total: Long? = null, uri: String? = null) {
        val values = ContentValues().apply { put("status", status); put("reason", reason); downloaded?.let { put("downloaded", it) }; total?.let { put("total", it) }; uri?.let { put("local_uri", it) } }
        writableDatabase.update("jobs", values, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun remove(id: Long) { writableDatabase.delete("jobs", "id=?", arrayOf(id.toString())) }
    @Synchronized fun requeueInterrupted() { writableDatabase.execSQL("UPDATE jobs SET status=?, reason='' WHERE status=?", arrayOf(DownloadStatus.PENDING, DownloadStatus.RUNNING)) }

    private companion object {
        const val ID_BASE = 1_000_000_000_000L
        const val DEFAULT_DB_NAME = "lowtube_download_queue.db"
    }
}
