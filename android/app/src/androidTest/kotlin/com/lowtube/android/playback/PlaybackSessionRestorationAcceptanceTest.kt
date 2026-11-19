package com.lowtube.android.playback

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.MainActivity
import com.lowtube.android.R
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackSessionStore
import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in UI acceptance for the saved player checkpoint across an external force-stop. */
class PlaybackSessionRestorationAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun seedsQueueOwnedPlaybackCheckpointForExternalProcessDeath() {
        assumeTrue(optedIn("runPlaybackSessionSeed"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionStore = PlaybackSessionStore(context)
        val existing = sessionStore.load()
        assumeTrue("The acceptance refuses to replace a non-test playback checkpoint", existing == null || existing.video.id == VIDEO_ID)
        val settings = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
        val fixtureState = context.getSharedPreferences(FIXTURE_PREFERENCES, Context.MODE_PRIVATE)
        fixtureState.edit()
            .putBoolean(KEY_FIRST_RUN_WAS_PRESENT, settings.contains("first_run_complete"))
            .putBoolean(KEY_FIRST_RUN_PREVIOUS_VALUE, settings.getBoolean("first_run_complete", false))
            .commit()
        settings.edit().putBoolean("first_run_complete", true).commit()
        val library = LocalLibraryRepository(context)
        try {
            library.queue().filter { it.video.id == VIDEO_ID }.forEach { library.removeQueueEntry(it.rowId) }
            val video = fixtureVideo()
            library.enqueue(video)
            val row = library.queue().single { it.video.id == VIDEO_ID }
            sessionStore.save(video, row.rowId, null, null)
            val saved = sessionStore.load()
            assertEquals(VIDEO_ID, saved?.video?.id)
            assertEquals(row.rowId, saved?.queueRowId)
        } finally {
            library.close()
        }
    }

    @Test
    fun restoresMiniPlayerAndQueueOwnershipAfterExternalProcessDeathWithoutAutoplay() {
        assumeTrue(optedIn("runPlaybackSessionRestore"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val library = LocalLibraryRepository(context)
        try {
            val row = library.queue().singleOrNull { it.video.id == VIDEO_ID }
            val saved = PlaybackSessionStore(context).load()
            assertNotNull("The checkpoint should survive the external force-stop", saved)
            assertEquals(VIDEO_ID, saved?.video?.id)
            assertNotNull("The queue row should still exist before Media3 reaches READY", row)
            assertEquals("Queue ownership should be restored with its exact row ID", row?.rowId, saved?.queueRowId)

            compose.waitUntil(10_000L) {
                compose.onAllNodesWithText(fixtureVideo().title).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(fixtureVideo().title).assertExists()
            compose.onNodeWithText(compose.activity.getString(R.string.mini_player_open)).assertExists()
            compose.onNodeWithText(compose.activity.getString(R.string.search_tab)).assertExists()
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithText(compose.activity.getString(R.string.mini_player_play))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("The restored mini-player should offer Play, not autoplay", compose.onAllNodesWithText(
                compose.activity.getString(R.string.mini_player_play),
            ).fetchSemanticsNodes().isNotEmpty())
            assertTrue("The restored mini-player must not show its active Pause control", compose.onAllNodesWithText(
                compose.activity.getString(R.string.mini_player_pause),
            ).fetchSemanticsNodes().isEmpty())
        } finally {
            library.close()
        }
    }

    @Test
    fun cleansPlaybackCheckpointAcceptanceFixture() {
        assumeTrue(optedIn("runPlaybackSessionCleanup"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val library = LocalLibraryRepository(context)
        try {
            library.queue().filter { it.video.id == VIDEO_ID }.forEach { library.removeQueueEntry(it.rowId) }
            if (PlaybackSessionStore(context).load()?.video?.id == VIDEO_ID) PlaybackSessionStore(context).clear()
            val settings = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
            val fixtureState = context.getSharedPreferences(FIXTURE_PREFERENCES, Context.MODE_PRIVATE)
            if (fixtureState.contains(KEY_FIRST_RUN_WAS_PRESENT)) {
                if (fixtureState.getBoolean(KEY_FIRST_RUN_WAS_PRESENT, false)) {
                    settings.edit()
                        .putBoolean("first_run_complete", fixtureState.getBoolean(KEY_FIRST_RUN_PREVIOUS_VALUE, false))
                        .commit()
                } else settings.edit().remove("first_run_complete").commit()
                fixtureState.edit().remove(KEY_FIRST_RUN_WAS_PRESENT).remove(KEY_FIRST_RUN_PREVIOUS_VALUE).commit()
            }
        } finally {
            library.close()
        }
    }

    private fun optedIn(name: String): Boolean =
        InstrumentationRegistry.getArguments().getString(name) == "true"

    private fun fixtureVideo() = VideoItem(
        id = VIDEO_ID,
        title = TITLE,
        channel = "LowTube acceptance fixture",
        duration = "1:00",
        url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        thumbnailUrl = "https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg",
    )

    private companion object {
        const val VIDEO_ID = "LTPrcDth001"
        const val TITLE = "LowTube process death checkpoint fixture"
        const val FIXTURE_PREFERENCES = "lowtube_process_death_acceptance"
        const val KEY_FIRST_RUN_WAS_PRESENT = "first_run_was_present"
        const val KEY_FIRST_RUN_PREVIOUS_VALUE = "first_run_previous_value"
    }
}
