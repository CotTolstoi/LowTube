package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import android.app.PictureInPictureParams
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lowtube.android.MainActivity
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
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PlaybackPictureInPictureAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeLocalVideoMakesActivityEligibleAndEntersLandscapeSystemPip() = runBlocking {
        activeLocalVideoMakesActivityEligibleAndEntersSystemPip("video", 16, 9)
    }

    @Test
    fun activePortraitShortsMakesActivityEligibleAndEntersPortraitSystemPip() = runBlocking {
        activeLocalVideoMakesActivityEligibleAndEntersSystemPip("shorts", 9, 16)
    }

    private suspend fun activeLocalVideoMakesActivityEligibleAndEntersSystemPip(
        kind: String,
        expectedWidth: Int,
        expectedHeight: Int,
    ) {
        assumeTrue("System Picture-in-Picture requires Android 8.0/API26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

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
        if (withContext(Dispatchers.Main.immediate) { controller.currentMediaItem != null }) {
            withContext(Dispatchers.Main.immediate) { MediaController.releaseFuture(controllerFuture) }
            assumeTrue("Refusing to replace an existing MediaSession item", false)
        }

        val isShorts = kind == "shorts"
        val videoFile = File(context.cacheDir, "lowtube-pip-${System.currentTimeMillis()}.${if (isShorts) "mp4" else "webm"}")
        val databaseName = "lowtube-pip-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val pipEligible = java.util.concurrent.atomic.AtomicBoolean(false)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val video = VideoItem(
            id = VIDEO_ID,
            title = "PiP acceptance local video",
            channel = "LowTube acceptance fixture",
            duration = "0:05",
            kind = kind,
            url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        try {
            if (isShorts) InstrumentationRegistryProvider.copyPortraitMp4Fixture(videoFile)
            else InstrumentationRegistryProvider.copyVp8Fixture(videoFile)
            val sourceUri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", videoFile)
            compose.activity.runOnUiThread {
                compose.activity.setContentView(ComposeView(compose.activity).apply {
                    setContent {
                        LowTubeTheme {
                            PlaybackScreen(
                                video = video,
                                playbackRequestId = REQUEST_ID,
                                isFollowing = false,
                                onToggleFollow = { false },
                                localAudioTrack = null,
                                localVideoSource = LocalVideoSource(sourceUri.toString(), if (isShorts) "video/mp4" else "video/webm"),
                                library = library,
                                playbackPreferences = PlaybackStreamPreferences(),
                                playbackSpeed = 1f,
                                resumePlayback = false,
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
                                onPictureInPictureEligibilityChanged = { eligible, isShorts ->
                                    pipEligible.set(eligible)
                                    setPipEligibility(compose.activity, eligible, isShorts)
                                },
                            )
                        }
                    }
                })
            }
            compose.waitUntil(15_000) { ready.get() }
            withTimeout(5_000) {
                while (true) {
                    val state = withContext(Dispatchers.Main.immediate) {
                        controller.playbackState to controller.currentPosition
                    }
                    assertTrue("The five-second fixture must stay under the two-minute cap", state.second < 120_000L)
                    if (pipEligible.get() && withContext(Dispatchers.Main.immediate) { controller.isPlaying }) break
                    delay(50)
                }
            }
            val startPosition = withContext(Dispatchers.Main.immediate) { controller.currentPosition }
            assertTrue("The test must observe real active Media3 playback", withContext(Dispatchers.Main.immediate) {
                controller.playbackState == Player.STATE_READY && controller.isPlaying
            })
            val pipParams = readPipParams(compose.activity)
            val aspectRatio = pipParams.aspectRatio ?: throw AssertionError("PiP aspect ratio is missing")
            assertEquals("PiP must use the aspect ratio for the selected content kind", expectedWidth, aspectRatio.numerator)
            assertEquals("PiP must use the aspect ratio for the selected content kind", expectedHeight, aspectRatio.denominator)

            withContext(Dispatchers.Main.immediate) { invokeLeaveHint(compose.activity) }
            compose.waitUntil(5_000) { compose.activity.isInPictureInPictureMode }
            val endPosition = withContext(Dispatchers.Main.immediate) { controller.currentPosition }
            assertTrue("The local media must advance during the PiP transition", endPosition > startPosition)
            assertTrue("The five-second fixture must not reach its end", endPosition < FIXTURE_DURATION_MS)
            assertTrue("The acceptance must remain below two minutes", endPosition < 120_000L)
        } finally {
            compose.activity.runOnUiThread { compose.activity.setContentView(FrameLayout(compose.activity)) }
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(controllerFuture)
            }
            library.close()
            videoFile.delete()
            context.deleteDatabase(databaseName)
        }
    }

    private fun setPipEligibility(activity: MainActivity, eligible: Boolean, isShorts: Boolean) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "setPictureInPictureEligibility",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(activity, eligible, isShorts)
    }

    private fun readPipParams(activity: MainActivity): PictureInPictureParams {
        val method = MainActivity::class.java.getDeclaredMethod("pictureInPictureParams")
        method.isAccessible = true
        return method.invoke(activity) as PictureInPictureParams
    }

    private fun invokeLeaveHint(activity: MainActivity) {
        val method = MainActivity::class.java.getDeclaredMethod("onUserLeaveHint")
        method.isAccessible = true
        method.invoke(activity)
    }

    private companion object {
        const val VIDEO_ID = "pipsystem01"
        const val REQUEST_ID = 814_251L
        const val FIXTURE_DURATION_MS = 5_000L
    }
}

private object InstrumentationRegistryProvider {
    fun copyVp8Fixture(destination: File) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("com/lowtube/android/downloads/webm_vp8_vorbis.webm")
            .use { input -> destination.outputStream().use(input::copyTo) }
    }

    fun copyPortraitMp4Fixture(destination: File) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val source = File(destination.parentFile, "${destination.nameWithoutExtension}-source.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            instrumentation.context.assets.open("com/lowtube/android/downloads/mux_fixture.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            }
            extractor.setDataSource(source.absolutePath)
            val videoTrack = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "video/avc"
            }
            val format = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)
            val outputMuxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outputMuxer
            val outputTrack = outputMuxer.addTrack(format)
            outputMuxer.setOrientationHint(90)
            outputMuxer.start()
            muxerStarted = true
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0 || extractor.sampleTime >= FIXTURE_DURATION_MS * 1000L) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                outputMuxer.writeSampleData(outputTrack, buffer, info)
                if (!extractor.advance()) break
            }
            outputMuxer.stop()
            muxerStarted = false
            val portrait = MediaExtractor()
            try {
                portrait.setDataSource(destination.absolutePath)
                val portraitTrack = (0 until portrait.trackCount).first { index ->
                    portrait.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "video/avc"
                }
                val portraitFormat = portrait.getTrackFormat(portraitTrack)
                assertEquals("The Shorts fixture must contain portrait rotation metadata", 90, portraitFormat.getInteger(MediaFormat.KEY_ROTATION))
                assertTrue("The portrait fixture must remain shorter than two minutes", portraitFormat.getLong(MediaFormat.KEY_DURATION) in 1L..120_000_000L)
            } finally {
                portrait.release()
            }
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            source.delete()
        }
    }

    private const val FIXTURE_DURATION_MS = 5_000L
}
