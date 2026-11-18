package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackService
import com.lowtube.android.data.InnerTubeSearchRepository
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

/** Opt-in network acceptance for the mini-player's MediaSession controls; playback stays under two minutes. */
@RunWith(AndroidJUnit4::class)
class MiniPlayerPlaybackAcceptanceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun miniPlayerPausesAndResumesTheCurrentMediaSessionItem() = runBlocking {
        assumeTrue(
            "Pass -e runMiniPlayerPlaybackAcceptance true after mini-player control changes",
            InstrumentationRegistry.getArguments().getString("runMiniPlayerPlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            channel = "Blender Foundation",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val stream = InnerTubeSearchRepository(context).resolvePlayback(video)
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
        suspend fun playbackSnapshot(): Pair<Boolean, Long> = withContext(Dispatchers.Main.immediate) {
            controller.isPlaying to controller.currentPosition
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                val metadata = MediaMetadata.Builder().setTitle(video.title).setArtist(video.channel).build()
                controller.setMediaItem(
                    MediaItem.Builder().setMediaId(video.id).setUri(stream.uri).setMediaMetadata(metadata).build(),
                )
                controller.prepare()
                withTimeout(45_000) {
                    while (controller.playbackState != Player.STATE_READY) delay(100)
                }
                controller.play()
            }
            withTimeout(20_000) {
                while (true) {
                    val (playing, position) = playbackSnapshot()
                    assertTrue("Mini-player acceptance exceeded two minutes", position < 120_000)
                    if (playing && position >= 1_500) break
                    delay(50)
                }
            }
            composeTestRule.setContent {
                LowTubeTheme { MiniPlayerBar(video = video, onOpenPlayer = {}) }
            }
            val pauseLabel = composeTestRule.activity.getString(com.lowtube.android.R.string.mini_player_pause)
            val playLabel = composeTestRule.activity.getString(com.lowtube.android.R.string.mini_player_play)
            composeTestRule.waitUntil(10_000) {
                composeTestRule.onAllNodesWithText(pauseLabel).fetchSemanticsNodes().isNotEmpty()
            }
            val beforePause = playbackSnapshot().second
            composeTestRule.onNodeWithText(pauseLabel).performClick()
            withTimeout(5_000) {
                while (playbackSnapshot().first) delay(50)
            }
            val pausedAt = playbackSnapshot().second
            delay(750)
            assertTrue("Mini-player pause should freeze media time", playbackSnapshot().second <= pausedAt + 250)

            composeTestRule.onNodeWithText(playLabel).performClick()
            withTimeout(10_000) {
                while (true) {
                    val (playing, position) = playbackSnapshot()
                    assertTrue("Mini-player acceptance exceeded two minutes", position < 120_000)
                    if (playing && position >= beforePause + 1_500) break
                    delay(50)
                }
            }
            assertTrue("Mini-player resume should advance media time", playbackSnapshot().second >= beforePause + 1_500)
        } finally {
            withContext(Dispatchers.Main.immediate) {
                controller.pause()
                controller.clearMediaItems()
                MediaController.releaseFuture(controllerFuture)
            }
        }
    }
}
