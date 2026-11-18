package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStreamPreferencesTest {
    @Test
    fun hasIndependentDesktopCompatibleDefaults() {
        val downloads = DownloadStreamPreferences()
        val playback = PlaybackStreamPreferences()

        assertEquals(720, downloads.maxHeight)
        assertTrue(downloads.h264Only)
        assertFalse(downloads.fps30Only)
        assertTrue(playback.fps30Only)
    }

    @Test
    fun mapsDownloadRulesIntoResolverPreferencesWithoutChangingAudioMode() {
        val resolved = DownloadStreamPreferences(1080, false, true).asPlaybackPreferences(audioOnly = true)

        assertEquals(1080, resolved.maxHeight)
        assertFalse(resolved.h264Only)
        assertTrue(resolved.fps30Only)
        assertTrue(resolved.audioOnly)
    }
}
