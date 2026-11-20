package com.lowtube.android.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** Keep persisted/UI playback speed within the range offered by the player controls. */
internal object PlaybackSpeedPolicy {
    fun clamp(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    fun apply(player: Player, speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(clamp(speed)))
    }

    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 2f
}
