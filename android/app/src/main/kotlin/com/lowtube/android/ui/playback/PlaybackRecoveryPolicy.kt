package com.lowtube.android.ui.playback

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

/** Bounded counterpart of Windows AutoRecover: at most two fresh-link retries per item session. */
internal object PlaybackRecoveryPolicy {
    const val MAX_RETRIES = 2

    fun shouldRetryResolution(autoRecover: Boolean, retriesUsed: Int, failure: Throwable): Boolean =
        autoRecover && retriesUsed in 0 until MAX_RETRIES && isTransientResolutionFailure(failure)

    fun shouldRetryPlayerError(autoRecover: Boolean, retriesUsed: Int, errorCode: Int): Boolean =
        autoRecover && retriesUsed in 0 until MAX_RETRIES && errorCode in RETRYABLE_PLAYER_ERRORS

    fun retryDelayMillis(nextAttempt: Int): Long = when (nextAttempt) {
        1 -> 1_000L
        2 -> 3_000L
        else -> 0L
    }

    private fun isTransientResolutionFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            when (current) {
                is InterruptedIOException, is UnknownHostException, is ConnectException, is SocketException -> return true
            }
            val message = current?.message.orEmpty()
            if (TRANSIENT_RESOLUTION_MESSAGE.containsMatchIn(message)) return true
            current = current?.cause
        }
        return false
    }

    private val RETRYABLE_PLAYER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )
    private val TRANSIENT_RESOLUTION_MESSAGE = Regex(
        "(?i)(?:HTTP\\s+(?:408|5\\d{2})\\b|temporarily unavailable|timed? ?out|connection (?:reset|closed|refused)|network is unreachable)",
    )
    private const val MAX_CAUSE_DEPTH = 12
}
