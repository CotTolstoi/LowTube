package com.lowtube.android.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.data.PlaybackSessionStore
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in bounded network acceptance for saved playback resumption in a fresh app process. */
@RunWith(AndroidJUnit4::class)
class PlaybackResumptionAfterProcessDeathAcceptanceTest {
    @Test
    fun seedsPublicVideoCheckpointAndPreservesPreviousState() {
        assumeTrue(optedIn("runPlaybackResumptionProcessDeathSeed"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionPreferences = context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
        val fixture = context.getSharedPreferences(FIXTURE_PREFERENCES, Context.MODE_PRIVATE)
        assumeTrue("An earlier process-death acceptance fixture is still present", !fixture.contains(KEY_BACKUP_COMPLETE))

        val settings = context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        fixture.edit()
            .putBoolean(KEY_BACKUP_COMPLETE, true)
            .putBoolean(KEY_SESSION_WAS_PRESENT, sessionPreferences.contains(SESSION_KEY))
            .putString(KEY_SESSION_BACKUP, sessionPreferences.getString(SESSION_KEY, null))
            .putBoolean(KEY_RESUME_WAS_PRESENT, settings.contains(RESUME_PREFERENCE))
            .putBoolean(KEY_RESUME_BACKUP, settings.getBoolean(RESUME_PREFERENCE, true))
            .commit()

        val sessionStore = PlaybackSessionStore(context)
        val existing = sessionStore.load()
        assumeTrue("Refusing to replace an unreadable existing playback checkpoint", !sessionPreferences.contains(SESSION_KEY) || existing != null)
        sessionStore.save(fixtureVideo(), null, null, null)
        settings.edit().putBoolean(RESUME_PREFERENCE, false).commit()
        assertEquals(VIDEO_ID, sessionStore.load()?.video?.id)
    }

    @Test
    fun resumesPublicVideoFromSavedCheckpointAfterExternalForceStop() = runBlocking {
        assumeTrue(optedIn("runPlaybackResumptionAfterProcessDeath"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(VIDEO_ID, PlaybackSessionStore(context).load()?.video?.id)
        val controllerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        try {
            val controller = withContext(Dispatchers.Main.immediate) {
                withTimeout(20_000) {
                    while (!controllerFuture.isDone) delay(50)
                    controllerFuture.get()
                }
            }
            withContext(Dispatchers.Main.immediate) { controller.play() }
            withTimeout(65_000) {
                while (true) {
                    val state = withContext(Dispatchers.Main.immediate) {
                        PlaybackStateSnapshot(
                            controller.isPlaying,
                            controller.currentPosition,
                            controller.currentMediaItem?.mediaId,
                            controller.playbackState,
                        )
                    }
                    assertTrue("Process-death playback acceptance exceeded two minutes", state.positionMs < MAX_TEST_PLAYBACK_MS)
                    if (state.mediaId == VIDEO_ID && state.isPlaying && state.positionMs >= MIN_ADVANCE_MS) break
                    if (state.playbackState == Player.STATE_IDLE && state.mediaId != null) {
                        throw AssertionError("The resumed player returned to IDLE before playback advanced")
                    }
                    delay(100)
                }
            }
        } finally {
            if (controllerFuture.isDone && !controllerFuture.isCancelled) {
                runCatching {
                    withContext(Dispatchers.Main.immediate) {
                        controllerFuture.get().pause()
                        controllerFuture.get().clearMediaItems()
                        MediaController.releaseFuture(controllerFuture)
                    }
                }
            }
        }
    }

    @Test
    fun restoresPreviousPlaybackCheckpointAndResumeSetting() {
        assumeTrue(optedIn("runPlaybackResumptionProcessDeathCleanup"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = context.getSharedPreferences(FIXTURE_PREFERENCES, Context.MODE_PRIVATE)
        if (!fixture.getBoolean(KEY_BACKUP_COMPLETE, false)) return

        val sessionPreferences = context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        val sessionEditor = sessionPreferences.edit()
        if (fixture.getBoolean(KEY_SESSION_WAS_PRESENT, false)) {
            sessionEditor.putString(SESSION_KEY, fixture.getString(KEY_SESSION_BACKUP, null))
        } else {
            sessionEditor.remove(SESSION_KEY)
        }
        sessionEditor.commit()

        val settingsEditor = settings.edit()
        if (fixture.getBoolean(KEY_RESUME_WAS_PRESENT, false)) {
            settingsEditor.putBoolean(RESUME_PREFERENCE, fixture.getBoolean(KEY_RESUME_BACKUP, true))
        } else {
            settingsEditor.remove(RESUME_PREFERENCE)
        }
        settingsEditor.commit()
        fixture.edit().clear().commit()
    }

    private fun optedIn(name: String): Boolean =
        InstrumentationRegistry.getArguments().getString(name) == "true"

    private fun fixtureVideo() = VideoItem(
        id = VIDEO_ID,
        title = "Big Buck Bunny",
        channel = "Blender Foundation",
        duration = "9:56",
        url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        thumbnailUrl = "https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg",
    )

    private data class PlaybackStateSnapshot(
        val isPlaying: Boolean,
        val positionMs: Long,
        val mediaId: String?,
        val playbackState: Int,
    )

    private companion object {
        const val VIDEO_ID = "aqz-KE-bpKQ"
        const val SESSION_PREFERENCES = "lowtube_playback_session"
        const val SESSION_KEY = "current_video_v1"
        const val SETTINGS_PREFERENCES = "lowtube_settings"
        const val RESUME_PREFERENCE = "resume_playback"
        const val FIXTURE_PREFERENCES = "lowtube_process_death_playback_acceptance"
        const val KEY_BACKUP_COMPLETE = "backup_complete"
        const val KEY_SESSION_WAS_PRESENT = "session_was_present"
        const val KEY_SESSION_BACKUP = "session_backup"
        const val KEY_RESUME_WAS_PRESENT = "resume_was_present"
        const val KEY_RESUME_BACKUP = "resume_backup"
        const val MIN_ADVANCE_MS = 1_500L
        const val MAX_TEST_PLAYBACK_MS = 120_000L
    }
}
