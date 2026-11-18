package com.lowtube.android.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun returnsSavedPositionWhenResumeIsEnabled() {
        assertEquals(42_000L, PlaybackResumePolicy.position(true, false) { 42_000L })
    }

    @Test
    fun startsAtZeroAndDoesNotReadHistoryWhenResumeIsDisabled() {
        var historyRead = false
        assertEquals(0L, PlaybackResumePolicy.position(false, false) { historyRead = true; 42_000L })
        assertFalse(historyRead)
    }

    @Test
    fun localAudioAlwaysStartsAtZeroWithoutReadingHistory() {
        var historyRead = false
        assertEquals(0L, PlaybackResumePolicy.position(true, true) { historyRead = true; 42_000L })
        assertFalse(historyRead)
    }
}
