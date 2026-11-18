package com.lowtube.android.data

/** Android equivalents of the PC buffer, memory-cap, back-buffer, and startup-buffer settings. */
data class PlaybackBufferPreferences(
    val maximumSeconds: Int = 60,
    val memoryLimitMiB: Int = 64,
    val backBufferMiB: Int = 4,
    val startupSeconds: Int = 3,
) {
    init {
        require(maximumSeconds in 5..3600) { "Playback buffer must be between 5 and 3,600 seconds." }
        require(memoryLimitMiB in 16..1024) { "Playback memory limit must be between 16 and 1,024 MiB." }
        require(backBufferMiB in 0..512) { "Back buffer must be between 0 and 512 MiB." }
        require(startupSeconds in 1..30 && startupSeconds <= maximumSeconds) {
            "Startup buffer must be between 1 and 30 seconds and no longer than the playback buffer."
        }
    }

    internal fun toMedia3Policy(): Media3BufferPolicy {
        val maximumMs = maximumSeconds * 1000
        return Media3BufferPolicy(
            minimumBufferMs = minOf(maximumMs, DEFAULT_MINIMUM_BUFFER_MS),
            maximumBufferMs = maximumMs,
            startupBufferMs = startupSeconds * 1000,
            targetBufferBytes = memoryLimitMiB * MEBIBYTE_BYTES,
            backBufferDurationMs = minOf(maximumMs, backBufferMiB * APPROXIMATE_BACK_BUFFER_MS_PER_MIB),
        )
    }

    companion object {
        const val DEFAULT_MAXIMUM_SECONDS = 60
        const val DEFAULT_MEMORY_LIMIT_MIB = 64
        const val DEFAULT_BACK_BUFFER_MIB = 4
        const val DEFAULT_STARTUP_SECONDS = 3

        fun fromStoredValues(maximumSeconds: Int, memoryLimitMiB: Int, backBufferMiB: Int, startupSeconds: Int) =
            runCatching {
                PlaybackBufferPreferences(maximumSeconds, memoryLimitMiB, backBufferMiB, startupSeconds)
            }.getOrDefault(PlaybackBufferPreferences())

        fun memoryLimitForPcDefault(maximumSeconds: Int): Int = when (maximumSeconds) {
            12 -> 32
            120 -> 128
            else -> DEFAULT_MEMORY_LIMIT_MIB
        }

        private const val DEFAULT_MINIMUM_BUFFER_MS = 50_000
        private const val MEBIBYTE_BYTES = 1024 * 1024

        /** Media3 accepts duration rather than bytes; 1 MiB holds about 1.05 s at 8 Mbps. */
        private const val APPROXIMATE_BACK_BUFFER_MS_PER_MIB = 1049
    }
}

internal data class Media3BufferPolicy(
    val minimumBufferMs: Int,
    val maximumBufferMs: Int,
    val startupBufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferDurationMs: Int,
)
