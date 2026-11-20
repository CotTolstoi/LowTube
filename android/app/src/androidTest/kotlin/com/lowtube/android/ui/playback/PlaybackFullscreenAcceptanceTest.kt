package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackFullscreenAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun landscapeFullscreenCanBeEnteredAndExitedWithoutLosingTheMediaSession() = runBlocking {
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

        val originalOrientation = compose.activity.requestedOrientation
        val originalConfigurationOrientation = compose.activity.resources.configuration.orientation
        val videoFile = File(context.cacheDir, "lowtube-fullscreen-${System.currentTimeMillis()}.webm")
        val databaseName = "lowtube-fullscreen-${System.currentTimeMillis()}.db"
        val library = LocalLibraryRepository(context, databaseName)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("com/lowtube/android/downloads/webm_vp8_vorbis.webm")
                .use { input -> videoFile.outputStream().use(input::copyTo) }
            val sourceUri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", videoFile)
            val video = VideoItem(
                id = "fullscreen01",
                title = "Fullscreen acceptance fixture",
                channel = "LowTube acceptance fixture",
                duration = "0:05",
                url = "https://www.youtube.com/watch?v=fullscreen01",
            )
            compose.activity.runOnUiThread {
                compose.activity.setContentView(ComposeView(compose.activity).apply {
                    setContent {
                        LowTubeTheme {
                            PlaybackScreen(
                                video = video,
                                playbackRequestId = 991_305L,
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
                            )
                        }
                    }
                })
            }
            compose.waitUntil(15_000) { ready.get() }
            withContext(Dispatchers.Main.immediate) { controller.pause() }
            assertTrue(
                "The local fixture must remain below the two-minute limit",
                withContext(Dispatchers.Main.immediate) { controller.currentPosition < 120_000L },
            )
            compose.onNodeWithTag(PLAYBACK_FULLSCREEN_ENTER_TAG).performClick()
            compose.waitUntil(5_000) {
                compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.onNodeWithTag(PLAYBACK_FULLSCREEN_EXIT_TAG).assertExists()

            compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) {
                compose.activity.requestedOrientation == originalOrientation &&
                    compose.activity.resources.configuration.orientation == originalConfigurationOrientation
            }
            compose.onNodeWithTag(PLAYBACK_FULLSCREEN_ENTER_TAG).assertExists()
            assertEquals(
                "Fullscreen must keep the prepared media item",
                video.id,
                withContext(Dispatchers.Main.immediate) { controller.currentMediaItem?.mediaId },
            )
            assertTrue(
                "The fullscreen check must stay below the two-minute limit",
                withContext(Dispatchers.Main.immediate) { controller.currentPosition < 120_000L },
            )
        } finally {
            compose.activity.runOnUiThread {
                compose.activity.requestedOrientation = originalOrientation
                compose.activity.setContentView(FrameLayout(compose.activity))
            }
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
}
