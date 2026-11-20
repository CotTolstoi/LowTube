package com.lowtube.android.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSpeedPolicyAcceptanceTest {
    @Test
    fun appliesSavedSpeedToAConnectedMedia3PlayerWithoutStartingPlayback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val player = ExoPlayer.Builder(context).build()
            try {
                PlaybackSpeedPolicy.apply(player, 1.5f)
                assertEquals(1.5f, player.playbackParameters.speed)
                assertEquals(1f, player.playbackParameters.pitch)
            } finally {
                player.release()
            }
        }
    }
}
