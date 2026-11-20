package com.lowtube.android.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C

/** Keep audio-only items classified as music for Android audio focus and system media controls. */
internal object PlaybackAudioAttributesPolicy {
    fun contentType(mimeType: String?): Int =
        if (mimeType?.startsWith("audio/", ignoreCase = true) == true) {
            C.AUDIO_CONTENT_TYPE_MUSIC
        } else {
            C.AUDIO_CONTENT_TYPE_MOVIE
        }

    fun audioAttributes(mimeType: String?): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(contentType(mimeType))
        .build()
}
