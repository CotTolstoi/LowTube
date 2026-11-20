package com.lowtube.android.ui.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPictureInPicturePolicyTest {
    @Test
    fun eligibilityRequiresReadyPlayingVideoAndAnActiveItem() {
        assertTrue(
            PlaybackPictureInPicturePolicy.isEligible(
                isPlaying = true,
                playbackState = Player.STATE_READY,
                hasCurrentItem = true,
                hasLocalAudioTrack = false,
                audioOnly = false,
            ),
        )
        assertFalse(isEligible(isPlaying = false))
        assertFalse(isEligible(playbackState = Player.STATE_BUFFERING))
        assertFalse(isEligible(hasCurrentItem = false))
        assertFalse(isEligible(hasLocalAudioTrack = true))
        assertFalse(isEligible(audioOnly = true))
    }

    @Test
    fun shortsUsePortraitPipAndRegularVideosUseLandscapePip() {
        assertEquals(PlaybackPipAspectRatio(9, 16), PlaybackPictureInPicturePolicy.aspectRatio(isShorts = true))
        assertEquals(PlaybackPipAspectRatio(16, 9), PlaybackPictureInPicturePolicy.aspectRatio(isShorts = false))
    }

    private fun isEligible(
        isPlaying: Boolean = true,
        playbackState: Int = Player.STATE_READY,
        hasCurrentItem: Boolean = true,
        hasLocalAudioTrack: Boolean = false,
        audioOnly: Boolean = false,
    ): Boolean = PlaybackPictureInPicturePolicy.isEligible(
        isPlaying = isPlaying,
        playbackState = playbackState,
        hasCurrentItem = hasCurrentItem,
        hasLocalAudioTrack = hasLocalAudioTrack,
        audioOnly = audioOnly,
    )
}
