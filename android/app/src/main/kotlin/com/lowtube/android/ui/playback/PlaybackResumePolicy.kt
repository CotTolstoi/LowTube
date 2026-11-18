package com.lowtube.android.ui.playback

/** Loads a saved watch position only when resume is enabled for a remote video. */
internal object PlaybackResumePolicy {
    fun position(resumePlayback: Boolean, isLocalAudio: Boolean, loadSavedPosition: () -> Long): Long {
        if (!resumePlayback || isLocalAudio) return 0L
        return loadSavedPosition().coerceAtLeast(0L)
    }
}
