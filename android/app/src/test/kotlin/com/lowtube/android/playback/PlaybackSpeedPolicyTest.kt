package com.lowtube.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test
    fun clampsPersistedSpeedToThePlayerControlRange() {
        assertEquals(0.25f, PlaybackSpeedPolicy.clamp(0.1f))
        assertEquals(1.25f, PlaybackSpeedPolicy.clamp(1.25f))
        assertEquals(2f, PlaybackSpeedPolicy.clamp(3f))
    }

}
