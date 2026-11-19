package com.lowtube.android.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.model.YouTubeSubscription
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSubscriptionCategoryTest {
    @Test
    fun categoryPersistsAndSurvivesChannelMetadataRefresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-channel-category-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val channelId = "UC" + "a".repeat(22)
        try {
            repository.follow(channelId, "Original title", "https://www.youtube.com/channel/$channelId")
            repository.setChannelCategory(channelId, "Science")

            repository.follow(channelId, "Updated title", "https://www.youtube.com/channel/$channelId")

            assertEquals("Science", repository.localChannels().single().category)
            assertEquals(listOf("Science"), repository.localSubscriptionCategories())
            repository.setChannelCategory(channelId, "")
            assertEquals("", repository.localChannels().single().category)
            assertEquals(emptyList<String>(), repository.localSubscriptionCategories())
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionSevenUpgradePreservesExistingSubscriptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-channel-category-migration-${System.currentTimeMillis()}.db"
        val channelId = "UC" + "b".repeat(22)
        val accountChannelId = "UC" + "d".repeat(22)
        val owner = "migrated@example.com"
        val versionSeven = VersionSevenDatabase(context, databaseName)
        val oldDb = versionSeven.writableDatabase
        oldDb.insert("subscriptions", null, ContentValues().apply {
            put("channel_id", channelId)
            put("title", "Existing channel")
            put("url", "https://www.youtube.com/channel/$channelId")
            put("added_at", 123L)
        })
        oldDb.insert("youtube_account_subscriptions", null, ContentValues().apply {
            put("channel_id", accountChannelId)
            put("owner_email", owner)
            put("title", "Existing account channel")
            put("url", "https://www.youtube.com/channel/$accountChannelId")
            put("synced_at", System.currentTimeMillis())
        })
        oldDb.close()
        versionSeven.close()

        val repository = LocalLibraryRepository(context, databaseName)
        try {
            assertEquals("Existing channel", repository.localChannels().single().title)
            assertEquals("", repository.localChannels().single().category)
            repository.setChannelCategory(channelId, "Music")
            assertEquals(listOf("Music"), repository.localSubscriptionCategories())
            assertEquals("", repository.channels(owner).single { it.id == accountChannelId }.category)
            repository.setChannelCategory(accountChannelId, "News", owner)
            assertEquals("News", repository.channels(owner).single { it.id == accountChannelId }.category)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun accountCategoriesSurviveSyncAndAreRemovedOnSignOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "lowtube-account-category-${System.currentTimeMillis()}.db"
        val repository = LocalLibraryRepository(context, databaseName)
        val channelId = "UC" + "c".repeat(22)
        val owner = "account@example.com"
        try {
            repository.replaceYouTubeAccountSubscriptions(owner, listOf(YouTubeSubscription(channelId, "Original title")))
            repository.setChannelCategory(channelId, "Education", owner)
            repository.replaceYouTubeAccountSubscriptions(owner, listOf(YouTubeSubscription(channelId, "Updated title")))

            val channel = repository.channels(owner).single()
            assertEquals("Updated title", channel.title)
            assertEquals("Education", channel.category)

            repository.clearYouTubeAccountSubscriptions()
            assertEquals(emptyList<com.lowtube.android.model.SavedChannel>(), repository.channels(owner))
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class VersionSevenDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 7) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE subscriptions (channel_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, added_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE playlist_items (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE queue_entries (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE watch_history (row_id INTEGER PRIMARY KEY AUTOINCREMENT)")
            db.execSQL("CREATE TABLE channel_feed_items (channel_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE channel_feed_state (channel_id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE youtube_account_subscriptions (channel_id TEXT PRIMARY KEY NOT NULL, owner_email TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, synced_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
