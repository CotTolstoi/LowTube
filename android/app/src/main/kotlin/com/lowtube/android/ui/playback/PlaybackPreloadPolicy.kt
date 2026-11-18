package com.lowtube.android.ui.playback

/** Bounded preloading policy aligned with the Windows client's 20-second preparation window. */
internal object PlaybackPreloadPolicy {
    const val START_BEFORE_END_MS = 20_000L

    fun shouldStart(
        enabled: Boolean,
        autoNext: Boolean,
        hasQueuedItem: Boolean,
        isLocalAudio: Boolean,
        isLive: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean,
        remainingMs: Long,
        attemptedForCurrentItem: Boolean,
    ): Boolean = enabled && autoNext && hasQueuedItem && !isLocalAudio && !isLive && isPlaying && !isBuffering &&
        !attemptedForCurrentItem && remainingMs in 1..START_BEFORE_END_MS
}
