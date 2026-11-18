package com.lowtube.android.ui.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestPolicyTest {
    @Test
    fun reusesMatchingReadyRequest() {
        assertTrue(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = 7L,
                requestedMediaId = VIDEO_ID,
                requestedRequestId = 7L,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun reloadsSameVideoForANewQueueRequest() {
        assertFalse(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = 7L,
                requestedMediaId = VIDEO_ID,
                requestedRequestId = 8L,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun doesNotReuseEndedRequestOrDifferentVideo() {
        assertFalse(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = 7L,
                requestedMediaId = VIDEO_ID,
                requestedRequestId = 7L,
                playbackState = Player.STATE_ENDED,
            ),
        )
        assertFalse(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = 7L,
                requestedMediaId = "another-video",
                requestedRequestId = 7L,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun preservesLegacyRestoredItemOnlyBeforeAnyExplicitRequest() {
        assertTrue(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = null,
                requestedMediaId = VIDEO_ID,
                requestedRequestId = 0L,
                playbackState = Player.STATE_BUFFERING,
            ),
        )
        assertFalse(
            PlaybackRequestPolicy.shouldReuseCurrentItem(
                currentMediaId = VIDEO_ID,
                currentRequestId = null,
                requestedMediaId = VIDEO_ID,
                requestedRequestId = 1L,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    private companion object {
        const val VIDEO_ID = "aqz-KE-bpKQ"
    }
}
