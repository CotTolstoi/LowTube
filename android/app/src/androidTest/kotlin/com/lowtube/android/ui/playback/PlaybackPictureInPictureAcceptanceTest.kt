package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackPictureInPictureAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeLocalVideoMakesActivityEligibleAndEntersSystemPip() = runBlocking {
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

        val videoFile = File(context.cacheDir, "lowtube-pip-${System.currentTimeMillis()}.webm")
        val databaseName = "lowtube-pip-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val pipEligible = java.util.concurrent.atomic.AtomicBoolean(false)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val video = VideoItem(
            id = VIDEO_ID,
            title = "PiP acceptance local video",
            channel = "LowTube acceptance fixture",
            duration = "0:05",
            kind = "video",
            url = "https://www.youtube.com/watch?v=$VIDEO_ID",
        )
        try {
            InstrumentationRegistryProvider.copyVp8Fixture(videoFile)
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
                                localVideoSource = LocalVideoSource(sourceUri.toString(), "video/webm"),
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
                                onPictureInPictureEligibilityChanged = { eligible ->
                                    pipEligible.set(eligible)
                                    setPipEligibility(compose.activity, eligible)
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

    private fun setPipEligibility(activity: MainActivity, eligible: Boolean) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "setPictureInPictureEligibility",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(activity, eligible)
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
}
