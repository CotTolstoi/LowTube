package com.lowtube.android.ui.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackFailureMessagesTest {
    @Test
    fun detectsLoginRequiredInNestedResolverFailure() {
        val failure = IOException("Playback failed", IOException("YouTube player API returned LOGIN_REQUIRED without a playable stream."))

        assertTrue(PlaybackFailureMessages.requiresYouTubeSignIn(failure))
    }

    @Test
    fun leavesOtherPlaybackErrorsUntouched() {
        assertFalse(PlaybackFailureMessages.requiresYouTubeSignIn(IOException("YouTube did not return a playable stream.")))
    }
}
