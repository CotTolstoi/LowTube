package com.lowtube.android.data

/** User-facing stream rules that map directly to the corresponding desktop playback options. */
data class PlaybackStreamPreferences(
    val maxHeight: Int = 720,
    val h264Only: Boolean = true,
    val fps30Only: Boolean = true,
    val audioOnly: Boolean = false,
) {
    init {
        require(maxHeight in SUPPORTED_HEIGHTS) { "Unsupported playback quality cap." }
    }

    companion object {
        val SUPPORTED_HEIGHTS = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}
