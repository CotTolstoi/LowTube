package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackService
import com.lowtube.android.ui.theme.LowTubeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PlaybackStartOverAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startOverSeeksCurrentMediaToZeroAndClearsSavedResumePosition() = runBlocking {
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

        val audioFile = File(context.cacheDir, "lowtube-start-over-${System.currentTimeMillis()}.wav")
        val databaseName = "lowtube-start-over-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val video = VideoItem(
            id = VIDEO_ID,
            title = "Start over acceptance fixture",
            channel = "LowTube acceptance fixture",
            duration = "0:08",
            url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            writeSilentWaveFile(audioFile, durationSeconds = 8)
            library.recordWatchProgress(video, positionMs = 6_000, durationMs = 30_000)
            val mediaItem = MediaItem.Builder()
                .setMediaId(video.id)
                .setUri(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", audioFile))
                .setMimeType("audio/wav")
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(video.title)
                        .setExtras(Bundle().apply { putLong(PLAYBACK_REQUEST_ID_EXTRA, REQUEST_ID) })
                        .build(),
                )
                .build()
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.setMediaItem(mediaItem)
                controller.prepare()
                withTimeout(15_000) {
                    while (controller.playbackState != Player.STATE_READY) delay(50)
                }
                controller.seekTo(1_000)
            }
            assertEquals(6_000L, library.resumePositionMs(video.id))

            compose.setContent {
                LowTubeTheme {
                    PlaybackScreen(
                        video = video,
                        playbackRequestId = REQUEST_ID,
                        isFollowing = false,
                        onToggleFollow = { false },
                        localAudioTrack = null,
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

            withContext(Dispatchers.Main.immediate) { controller.play() }
            withTimeout(5_000) {
                var hasAdvanced = false
                while (!hasAdvanced) {
                    val state = withContext(Dispatchers.Main.immediate) { controller.isPlaying to controller.currentPosition }
                    assertTrue("Synthetic playback must remain below two minutes", state.second < 120_000L)
                    hasAdvanced = state.first && state.second >= 500L
                    delay(50)
                }
            }
            withContext(Dispatchers.Main.immediate) { controller.pause() }
            compose.onNodeWithTag(PLAYBACK_START_OVER_TEST_TAG).performClick()
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main.immediate) { controller.currentPosition > 150L }) delay(50)
            }

            assertTrue("PlaybackScreen should have reused the prepared synthetic item", ready.get())
            assertEquals(0L, library.resumePositionMs(video.id))
            assertEquals("Start over must keep the history row", 1, library.watchHistory().size)
            assertFalse("Start over must not start playback automatically", withContext(Dispatchers.Main.immediate) { controller.isPlaying })
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
        const val VIDEO_ID = "startover01"
        const val REQUEST_ID = 814_240L
    }
}
