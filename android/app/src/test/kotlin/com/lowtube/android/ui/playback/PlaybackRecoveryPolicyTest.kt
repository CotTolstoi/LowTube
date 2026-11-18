package com.lowtube.android.ui.playback

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun retriesTransientResolutionFailuresOnlyWithinBound() {
        assertTrue(PlaybackRecoveryPolicy.shouldRetryResolution(true, 0, SocketTimeoutException("timed out")))
        assertTrue(PlaybackRecoveryPolicy.shouldRetryResolution(true, 1, IOException("YouTube request failed with HTTP 503.")))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryResolution(false, 0, SocketTimeoutException("timed out")))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryResolution(true, 2, SocketTimeoutException("timed out")))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryResolution(true, 0, IOException("The video requires an unsupported signature.")))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryResolution(true, 0, IOException("YouTube paused catalogue requests. Retry in 60 s.")))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryResolution(true, 0, IOException("YouTube request failed with HTTP 403.")))
    }

    @Test
    fun retriesOnlyRecoverableMediaIoErrors() {
        assertTrue(
            PlaybackRecoveryPolicy.shouldRetryPlayerError(
                true,
                0,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ),
        )
        assertTrue(
            PlaybackRecoveryPolicy.shouldRetryPlayerError(
                true,
                1,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ),
        )
        assertFalse(
            PlaybackRecoveryPolicy.shouldRetryPlayerError(
                true,
                2,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
        )
        assertFalse(PlaybackRecoveryPolicy.shouldRetryPlayerError(false, 0, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertFalse(PlaybackRecoveryPolicy.shouldRetryPlayerError(true, 0, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }

    @Test
    fun followsWindowsTwoAttemptBackoff() {
        assertEquals(1_000L, PlaybackRecoveryPolicy.retryDelayMillis(1))
        assertEquals(3_000L, PlaybackRecoveryPolicy.retryDelayMillis(2))
        assertEquals(0L, PlaybackRecoveryPolicy.retryDelayMillis(3))
        assertEquals(2, PlaybackRecoveryPolicy.MAX_RETRIES)
    }
}
