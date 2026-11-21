package com.lowtube.android.data

/** User-facing stream rules that map directly to the corresponding desktop playback options. */
data class PlaybackStreamPreferences(
    val maxHeight: Int = 720,
    val h264Only: Boolean = true,
    val fps30Only: Boolean = true,
    val audioOnly: Boolean = false,
    val decoderMode: PlaybackDecoderMode = PlaybackDecoderMode.SYSTEM_DEFAULT,
) {
    init {
        require(maxHeight in SUPPORTED_HEIGHTS) { "Unsupported playback quality cap." }
    }

    companion object {
        val SUPPORTED_HEIGHTS = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}

/** Android mapping for the desktop decoder choice; this is a preference, not a hardware guarantee. */
enum class PlaybackDecoderMode(val transferValue: String) {
    SYSTEM_DEFAULT("auto"),
    HARDWARE_PREFERRED("auto-copy"),
    SOFTWARE_PREFERRED("no");

    companion object {
        fun fromStoredValue(value: String?): PlaybackDecoderMode = when (value) {
            HARDWARE_PREFERRED.transferValue, "d3d11va", "d3d11va-copy", "dxva2", "dxva2-copy", "nvdec", "nvdec-copy" -> HARDWARE_PREFERRED
            SOFTWARE_PREFERRED.transferValue -> SOFTWARE_PREFERRED
            else -> SYSTEM_DEFAULT
        }
    }
}
