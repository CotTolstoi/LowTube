package com.lowtube.android.data

import android.content.SharedPreferences

/** Values chosen in the first-run setup, applied together with the completion marker. */
data class FirstRunSetupSelection(
    val language: String,
    val uiScale: Float,
    val darkTheme: Boolean,
    val playback: PlaybackStreamPreferences,
    val downloads: DownloadStreamPreferences,
)

/** Persists the setup form to the same keys used by Settings and playback/download resolution. */
object FirstRunSetupPreferences {
    fun save(preferences: SharedPreferences, selection: FirstRunSetupSelection) {
        preferences.edit()
            .putBoolean("first_run_complete", true)
            .putBoolean("dark_theme", selection.darkTheme)
            .putFloat("ui_scale", UiScaleOptions.normalize(selection.uiScale))
            .putInt("playback_height", selection.playback.maxHeight)
            .putBoolean("playback_h264", selection.playback.h264Only)
            .putBoolean("playback_fps30", selection.playback.fps30Only)
            .putBoolean("playback_audio_only", selection.playback.audioOnly)
            .putString("playback_decoder_mode", selection.playback.decoderMode.transferValue)
            .putInt("download_height", selection.downloads.maxHeight)
            .putBoolean("download_h264", selection.downloads.h264Only)
            .putBoolean("download_fps30", selection.downloads.fps30Only)
            .putInt("download_audio_bitrate", selection.downloads.audioBitrateKbps)
            .apply()
    }
}
