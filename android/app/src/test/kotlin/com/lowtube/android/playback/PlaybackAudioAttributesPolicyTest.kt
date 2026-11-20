package com.lowtube.android.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAudioAttributesPolicyTest {
    @Test
    fun classifiesAudioAndVideoForSystemAudioFocus() {
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, PlaybackAudioAttributesPolicy.contentType("audio/mp4"))
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, PlaybackAudioAttributesPolicy.contentType("AUDIO/MPEG"))
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, PlaybackAudioAttributesPolicy.contentType("video/mp4"))
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, PlaybackAudioAttributesPolicy.contentType("application/dash+xml"))
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, PlaybackAudioAttributesPolicy.contentType(null))
    }
}
