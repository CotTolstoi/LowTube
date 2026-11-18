package com.lowtube.android.ui.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreloadPolicyTest {
    @Test
    fun startsOnceWithinTwentySecondsWhenQueueCanAdvance() {
        assertTrue(PlaybackPreloadPolicy.shouldStart(true, true, true, false, false, true, false, 20_000, false))
        assertTrue(PlaybackPreloadPolicy.shouldStart(true, true, true, false, false, true, false, 1, false))
        assertFalse(PlaybackPreloadPolicy.shouldStart(true, true, true, false, false, true, false, 20_001, false))
        assertFalse(PlaybackPreloadPolicy.shouldStart(true, true, true, false, false, true, false, 20_000, true))
    }

    @Test
    fun doesNotPreloadWhenDisabledPausedBufferingLiveOrWithoutQueue() {
        fun decide(
            enabled: Boolean = true,
            autoNext: Boolean = true,
            hasQueue: Boolean = true,
            localAudio: Boolean = false,
            live: Boolean = false,
            playing: Boolean = true,
            buffering: Boolean = false,
            attempted: Boolean = false,
        ) = PlaybackPreloadPolicy.shouldStart(
            enabled, autoNext, hasQueue, localAudio, live, playing, buffering, 10_000, attempted,
        )
        assertFalse(decide(enabled = false))
        assertFalse(decide(autoNext = false))
        assertFalse(decide(hasQueue = false))
        assertFalse(decide(localAudio = true))
        assertFalse(decide(live = true))
        assertFalse(decide(playing = false))
        assertFalse(decide(buffering = true))
        assertFalse(decide(attempted = true))
    }
}
