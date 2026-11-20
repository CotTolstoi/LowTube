package com.lowtube.android.ui.playback

import androidx.media3.common.Player

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
}
