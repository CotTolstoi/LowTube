package com.lowtube.android.ui.playback

/** Keeps touch-seek targets inside a media item's valid timeline. */
internal object PlaybackSeekPolicy {
    fun target(currentPositionMs: Long, durationMs: Long, offsetMs: Long): Long? {
        if (currentPositionMs < 0L || offsetMs == 0L) return null
        val rawTarget = when {
            offsetMs > 0L && currentPositionMs > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
            offsetMs < 0L && currentPositionMs < Long.MIN_VALUE - offsetMs -> Long.MIN_VALUE
            else -> currentPositionMs + offsetMs
        }
        val nonNegative = rawTarget.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }
}
