package com.lowtube.android.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunSetupPreferencesTest {
    @Test
    fun setupSelectionIsWrittenToThePreferencesConsumedByTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("first-run-setup-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            FirstRunSetupPreferences.save(
                preferences,
                FirstRunSetupSelection(
                    language = "uk",
                    uiScale = 1.15f,
                    darkTheme = false,
                    playback = PlaybackStreamPreferences(maxHeight = 480, h264Only = false, fps30Only = false, audioOnly = true),
                    downloads = DownloadStreamPreferences(maxHeight = 1080, h264Only = false, fps30Only = true, audioBitrateKbps = 320),
                ),
            )

            assertTrue(preferences.getBoolean("first_run_complete", false))
            assertEquals(1.15f, preferences.getFloat("ui_scale", 1f))
            assertEquals(false, preferences.getBoolean("dark_theme", true))
            assertEquals(480, preferences.getInt("playback_height", -1))
            assertEquals(false, preferences.getBoolean("playback_h264", true))
            assertEquals(false, preferences.getBoolean("playback_fps30", true))
            assertEquals(true, preferences.getBoolean("playback_audio_only", false))
            assertEquals(1080, preferences.getInt("download_height", -1))
            assertEquals(false, preferences.getBoolean("download_h264", true))
            assertEquals(true, preferences.getBoolean("download_fps30", false))
            assertEquals(320, preferences.getInt("download_audio_bitrate", -1))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
