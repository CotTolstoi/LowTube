package com.lowtube.android.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSeekPolicyTest {
    @Test
    fun forwardAndBackwardTargetsRespectTimelineBoundaries() {
        assertEquals(20_000L, PlaybackSeekPolicy.target(10_000L, 60_000L, 10_000L))
        assertEquals(0L, PlaybackSeekPolicy.target(5_000L, 60_000L, -10_000L))
        assertEquals(60_000L, PlaybackSeekPolicy.target(55_000L, 60_000L, 10_000L))
    }

    @Test
    fun unknownDurationAllowsForwardSeekButInvalidOffsetsAreIgnored() {
        assertEquals(20_000L, PlaybackSeekPolicy.target(10_000L, -1L, 10_000L))
        assertNull(PlaybackSeekPolicy.target(10_000L, 60_000L, 0L))
        assertNull(PlaybackSeekPolicy.target(-1L, 60_000L, 10_000L))
    }
}
