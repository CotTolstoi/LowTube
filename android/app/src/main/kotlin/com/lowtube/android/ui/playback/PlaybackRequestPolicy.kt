package com.lowtube.android.ui.playback

import android.os.Bundle
import androidx.media3.common.Player

internal const val PLAYBACK_REQUEST_ID_EXTRA = "com.lowtube.android.playback.request_id"

/** Avoids reloading an already active request while allowing the same video to be explicitly replayed. */
internal object PlaybackRequestPolicy {
    fun requestId(extras: Bundle?): Long? =
        extras?.takeIf { it.containsKey(PLAYBACK_REQUEST_ID_EXTRA) }?.getLong(PLAYBACK_REQUEST_ID_EXTRA)

    fun shouldReuseCurrentItem(
        currentMediaId: String?,
        currentRequestId: Long?,
        requestedMediaId: String,
        requestedRequestId: Long,
        playbackState: Int,
    ): Boolean {
        if (currentMediaId != requestedMediaId ||
            playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED
        ) return false

        return currentRequestId == requestedRequestId ||
            (currentRequestId == null && requestedRequestId == 0L)
    }
}
