package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin

/** Verifies the real Compose reconnect branch; prepares a local item but never starts playback. */
@RunWith(AndroidJUnit4::class)
class PlaybackScreenSpeedReconnectAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reconnectingToTheCurrentMediaItemAppliesThePersistedSpeed() = runBlocking {
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
        val hasNoCurrentItem = withContext(Dispatchers.Main.immediate) { controller.currentMediaItem == null }
        if (!hasNoCurrentItem) {
            withContext(Dispatchers.Main.immediate) { MediaController.releaseFuture(controllerFuture) }
            assumeTrue("Refusing to replace an existing MediaSession item", false)
        }

        val settings = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
        val hadSpeed = settings.contains(SPEED_PREFERENCE)
        val oldSpeed = settings.getFloat(SPEED_PREFERENCE, 1f)
        val oldPlayerSpeed = withContext(Dispatchers.Main.immediate) { controller.playbackParameters.speed }
        val audioFile = File(context.cacheDir, "lowtube-speed-reconnect-${System.currentTimeMillis()}.wav")
        val databaseName = "lowtube-speed-reconnect-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            writeWaveFile(audioFile, durationSeconds = 6)
            val video = VideoItem(
                id = VIDEO_ID,
                title = "Playback speed reconnect fixture",
                channel = "LowTube acceptance fixture",
                duration = "0:06",
                url = "https://www.youtube.com/watch?v=$VIDEO_ID",
            )
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(video.title)
                .setExtras(Bundle().apply { putLong(PLAYBACK_REQUEST_ID_EXTRA, REQUEST_ID) })
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(video.id)
                .setUri(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", audioFile))
                .setMimeType("audio/wav")
                .setMediaMetadata(mediaMetadata)
                .build()
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.setPlaybackParameters(androidx.media3.common.PlaybackParameters(1f))
                controller.setMediaItem(mediaItem)
                controller.prepare()
                withTimeout(15_000) {
                    while (controller.playbackState != Player.STATE_READY) delay(50)
                }
            }
            settings.edit().putFloat(SPEED_PREFERENCE, SAVED_SPEED).commit()
            val persistedSpeed = settings.getFloat(SPEED_PREFERENCE, 1f)

            compose.setContent {
                LowTubeTheme {
                    PlaybackScreen(
                        video = video,
                        playbackRequestId = REQUEST_ID,
                        isFollowing = false,
                        onToggleFollow = { false },
                        localAudioTrack = null,
                        library = library,
                        playbackPreferences = PlaybackStreamPreferences(),
                        playbackSpeed = persistedSpeed,
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
            val actualSpeed = withContext(Dispatchers.Main.immediate) { controller.playbackParameters.speed }
            assertEquals("PlaybackScreen must reapply the saved speed on the current Media3 session", SAVED_SPEED, actualSpeed, 0f)
        } finally {
            compose.activity.runOnUiThread {
                compose.activity.setContentView(android.widget.FrameLayout(compose.activity))
            }
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.clearMediaItems()
                controller.setPlaybackParameters(androidx.media3.common.PlaybackParameters(oldPlayerSpeed))
                MediaController.releaseFuture(controllerFuture)
            }
            settings.edit().apply {
                if (hadSpeed) putFloat(SPEED_PREFERENCE, oldSpeed) else remove(SPEED_PREFERENCE)
            }.commit()
            library.close()
            audioFile.delete()
            context.deleteDatabase(databaseName)
        }
    }

    private fun writeWaveFile(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val sampleCount = sampleRate * durationSeconds
        val dataBytes = sampleCount * 2
        FileOutputStream(file).use { output ->
            fun writeInt(value: Int) { repeat(4) { offset -> output.write(value ushr (offset * 8) and 0xff) } }
            fun writeShort(value: Int) { output.write(value and 0xff); output.write(value ushr 8 and 0xff) }
            output.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeInt(16)
            writeShort(1); writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2)
            writeShort(2); writeShort(16); output.write("data".toByteArray(Charsets.US_ASCII)); writeInt(dataBytes)
            repeat(sampleCount) { index ->
                val sample = (sin(2.0 * Math.PI * 440.0 * index / sampleRate) * Short.MAX_VALUE * 0.2).toInt()
                writeShort(sample)
            }
        }
    }

    private companion object {
        const val VIDEO_ID = "aqz-KE-bpKQ"
        const val REQUEST_ID = 77125L
        const val SPEED_PREFERENCE = "playback_speed"
        const val SAVED_SPEED = 1.5f
    }
}
