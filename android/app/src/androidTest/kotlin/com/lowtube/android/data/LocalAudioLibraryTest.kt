package com.lowtube.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.LocalAudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioLibraryTest {
    @Test
    fun persistsDeduplicatesAndRemovesPersistedAudioUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-local-audio-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        try {
            val uri = "content://com.example.documents/document/audio%3A123"
            assertTrue(repository.saveLocalAudioTrack(uri, "Test Track.mp3", "audio/mpeg"))
            assertEquals(listOf(LocalAudioTrack(uri, "Test Track.mp3", "audio/mpeg", repository.localAudioTracks().single().addedAt)), repository.localAudioTracks())

            assertTrue(repository.saveLocalAudioTrack(uri, "Renamed Track.mp3", "audio/mpeg"))
            assertEquals(1, repository.localAudioTracks().size)
            assertEquals("Renamed Track.mp3", repository.localAudioTracks().single().title)
            assertFalse(repository.removeLocalAudioTrack("content://com.example.documents/document/missing"))
            assertTrue(repository.removeLocalAudioTrack(uri))
            assertTrue(repository.localAudioTracks().isEmpty())
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun databaseVersionFiveUpgradeCreatesAudioLibraryTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-local-audio-migration-${System.currentTimeMillis()}.db"
        val versionFive = VersionFiveDatabase(context, databaseName)
        versionFive.writableDatabase.close()
        versionFive.close()

        val repository = LocalLibraryRepository(context, databaseName)
        try {
            assertTrue(repository.localAudioTracks().isEmpty())
            assertTrue(repository.saveLocalAudioTrack("content://com.example.documents/document/audio%3A456", "Migrated.mp3", "audio/mpeg"))
            assertEquals("Migrated.mp3", repository.localAudioTracks().single().title)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class VersionFiveDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE subscriptions (channel_id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE playlist_items (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE queue_entries (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE watch_history (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE channel_feed_items (channel_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE channel_feed_state (channel_id TEXT PRIMARY KEY NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
