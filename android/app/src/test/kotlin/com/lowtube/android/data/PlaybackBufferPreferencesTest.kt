package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackBufferPreferencesTest {
    @Test
    fun mapsWindowsDefaultsToBoundedMedia3Values() {
        val policy = PlaybackBufferPreferences().toMedia3Policy()

        assertEquals(50_000, policy.minimumBufferMs)
        assertEquals(60_000, policy.maximumBufferMs)
        assertEquals(3_000, policy.startupBufferMs)
        assertEquals(64 * 1024 * 1024, policy.targetBufferBytes)
        assertEquals(4_196, policy.backBufferDurationMs)
    }

    @Test
    fun clampsMinimumAndBackBufferToConfiguredMaximum() {
        val policy = PlaybackBufferPreferences(
            maximumSeconds = 5,
            memoryLimitMiB = 1024,
            backBufferMiB = 512,
            startupSeconds = 5,
        ).toMedia3Policy()

        assertEquals(5_000, policy.minimumBufferMs)
        assertEquals(5_000, policy.maximumBufferMs)
        assertEquals(5_000, policy.startupBufferMs)
        assertEquals(1024 * 1024 * 1024, policy.targetBufferBytes)
        assertEquals(5_000, policy.backBufferDurationMs)
    }

    @Test
    fun rejectsUnsafeRangesAndStartupBufferLongerThanMaximum() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackBufferPreferences(maximumSeconds = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackBufferPreferences(memoryLimitMiB = 15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackBufferPreferences(startupSeconds = 4, maximumSeconds = 3)
        }
    }

    @Test
    fun invalidStoredSettingsFallBackToBoundedDefaults() {
        assertEquals(
            PlaybackBufferPreferences(),
            PlaybackBufferPreferences.fromStoredValues(5, 64, 4, 30),
        )
    }
}
