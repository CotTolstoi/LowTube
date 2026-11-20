package com.lowtube.android.ui.playback

import androidx.media3.common.Player

internal data class PlaybackPipAspectRatio(val width: Int, val height: Int)

internal object PlaybackPictureInPicturePolicy {
    fun isEligible(
        isPlaying: Boolean,
        playbackState: Int,
        hasCurrentItem: Boolean,
        hasLocalAudioTrack: Boolean,
        audioOnly: Boolean,
    ): Boolean = isPlaying &&
        playbackState == Player.STATE_READY &&
        hasCurrentItem &&
        !hasLocalAudioTrack &&
        !audioOnly

    fun aspectRatio(isShorts: Boolean): PlaybackPipAspectRatio =
        if (isShorts) PlaybackPipAspectRatio(9, 16) else PlaybackPipAspectRatio(16, 9)
}
