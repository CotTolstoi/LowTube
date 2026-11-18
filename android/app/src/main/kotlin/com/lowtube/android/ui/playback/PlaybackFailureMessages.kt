package com.lowtube.android.ui.playback

import android.content.Context
import com.lowtube.android.R

internal object PlaybackFailureMessages {
    fun text(context: Context, failure: Throwable?): String = when {
        failure == null -> context.getString(R.string.playback_failed)
        requiresYouTubeSignIn(failure) -> context.getString(R.string.playback_sign_in_required)
        else -> failure.message ?: context.getString(R.string.playback_failed)
    }

    internal fun requiresYouTubeSignIn(failure: Throwable?): Boolean {
        var current: Throwable? = failure
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (current != null && visited.add(current)) {
            if (LOGIN_REQUIRED.containsMatchIn(current.message.orEmpty())) return true
            current = current.cause
        }
        return false
    }

    private val LOGIN_REQUIRED = Regex("\\bLOGIN_REQUIRED\\b", RegexOption.IGNORE_CASE)
}
