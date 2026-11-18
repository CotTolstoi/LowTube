package com.lowtube.android.data

/** Download selection limits mirror the separate Windows download defaults. */
data class DownloadStreamPreferences(
    val maxHeight: Int = 720,
    val h264Only: Boolean = true,
    val fps30Only: Boolean = false,
    val limitKiB: Int = 0,
) {
    init {
        require(maxHeight in PlaybackStreamPreferences.SUPPORTED_HEIGHTS) {
            "Unsupported download quality cap."
        }
        require(limitKiB in 0..1_048_576) { "Download speed limit must be between 0 and 1,048,576 KiB/s." }
    }

    fun asPlaybackPreferences(audioOnly: Boolean = false) = PlaybackStreamPreferences(
        maxHeight = maxHeight,
        h264Only = h264Only,
        fps30Only = fps30Only,
        audioOnly = audioOnly,
    )
}
