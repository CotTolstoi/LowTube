package com.lowtube.android.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lowtube.android.data.PlaybackDecoderMode

/** Maps the user's decoder preference to Media3's platform codec ordering. */
@UnstableApi
internal object PlaybackDecoderSelector {
    fun forMode(mode: PlaybackDecoderMode): MediaCodecSelector = when (mode) {
        PlaybackDecoderMode.SYSTEM_DEFAULT -> MediaCodecSelector.DEFAULT
        PlaybackDecoderMode.SOFTWARE_PREFERRED -> softwarePreferred
        PlaybackDecoderMode.HARDWARE_PREFERRED -> hardwarePreferred
    }

    private val hardwarePreferred = object : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!mimeType.startsWith("video/")) return available
            val (hardware, other) = available.partition { it.hardwareAccelerated }
            // Keep non-hardware codecs as fallback if the preferred codecs fail to initialize.
            return if (hardware.isEmpty()) available else hardware + other
        }
    }

    private val softwarePreferred = object : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!mimeType.startsWith("video/")) return available
            val (software, other) = available.partition { it.softwareOnly }
            return if (software.isEmpty()) available else software + other
        }
    }
}
