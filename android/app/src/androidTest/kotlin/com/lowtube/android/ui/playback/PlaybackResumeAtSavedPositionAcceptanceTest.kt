package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.model.LocalVideoSource
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackService
import com.lowtube.android.ui.theme.LowTubeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PlaybackResumeAtSavedPositionAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun media3StartsFromSavedNonzeroWatchHistoryPosition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controllerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        val controller = withContext(Dispatchers.Main.immediate) {
            withTimeout(20_000) {
                while (!controllerFuture.isDone) delay(50)
                controllerFuture.get()
            }
        }
        val isIdle = withContext(Dispatchers.Main.immediate) { controller.currentMediaItem == null }
        if (!isIdle) {
            withContext(Dispatchers.Main.immediate) { MediaController.releaseFuture(controllerFuture) }
            assumeTrue("Refusing to replace an existing MediaSession item", false)
        }

        val audioFile = File(context.cacheDir, "lowtube-resume-history-${System.currentTimeMillis()}.wav")
        val databaseName = "lowtube-resume-history-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val video = VideoItem(
            id = VIDEO_ID,
            title = "Saved resume position acceptance fixture",
            channel = "LowTube acceptance fixture",
            duration = "0:30",
            url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            writeSilentWaveFile(audioFile, durationSeconds = 8)
            library.recordWatchProgress(video, positionMs = SAVED_POSITION_MS, durationMs = 30_000)
            assertTrue(library.resumePositionMs(video.id) == SAVED_POSITION_MS)
            val sourceUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", audioFile)

            compose.setContent {
                LowTubeTheme {
                    PlaybackScreen(
                        video = video,
                        playbackRequestId = REQUEST_ID,
                        isFollowing = false,
                        onToggleFollow = { false },
                        localAudioTrack = null,
                        localVideoSource = LocalVideoSource(sourceUri.toString(), "audio/wav"),
                        library = library,
                        playbackPreferences = PlaybackStreamPreferences(audioOnly = true),
                        playbackSpeed = 1f,
                        resumePlayback = true,
                        preloadNext = false,
                        onPlaybackSpeedChanged = {},
                        autoNext = false,
                        hasQueuedItems = false,
                        queueHeadRowId = null,
                        onResolveNextQueueEntry = { null },
                        onIsQueueHead = { false },
                        onPreloadedQueueEntryStarted = {},
                        onPlaybackReady = { ready.set(true) },
                        onAdvanceQueue = {},
                        onPreviousVideo = {},
                        onBack = {},
                    )
                }
            }
            compose.waitUntil(15_000) { ready.get() }
            withTimeout(2_000) {
                while (true) {
                    val position = withContext(Dispatchers.Main.immediate) { controller.currentPosition }
                    assertTrue("Synthetic playback must remain below two minutes", position < 120_000L)
                    if (position >= SAVED_POSITION_MS + 500L) break
                    delay(50)
                }
            }
            val positionAfterResume = withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.currentPosition
            }

            assertTrue("Media3 should begin at the saved history position", positionAfterResume >= SAVED_POSITION_MS + 500L)
            assertTrue("The 8-second fixture must not finish during acceptance", positionAfterResume < 8_000L)
            assertTrue("The check must remain below the two-minute media cap", positionAfterResume < 120_000L)
        } finally {
            compose.activity.runOnUiThread {
                compose.activity.setContentView(android.widget.FrameLayout(compose.activity))
            }
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(controllerFuture)
            }
            library.close()
            audioFile.delete()
            context.deleteDatabase(databaseName)
        }
    }

    private fun writeSilentWaveFile(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val dataBytes = sampleRate * durationSeconds * 2
        FileOutputStream(file).use { output ->
            fun writeInt(value: Int) { repeat(4) { offset -> output.write(value ushr (offset * 8) and 0xff) } }
            fun writeShort(value: Int) { output.write(value and 0xff); output.write(value ushr 8 and 0xff) }
            output.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeInt(16)
            writeShort(1); writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2)
            writeShort(2); writeShort(16); output.write("data".toByteArray(Charsets.US_ASCII)); writeInt(dataBytes)
            repeat(dataBytes) { output.write(0) }
        }
    }

    private companion object {
        const val VIDEO_ID = "resumepoint"
        const val REQUEST_ID = 814_241L
        const val SAVED_POSITION_MS = 6_000L
    }
}
