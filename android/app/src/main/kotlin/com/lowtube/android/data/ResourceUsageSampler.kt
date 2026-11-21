package com.lowtube.android.data

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal data class ResourceUsageSample(
    val durationMs: Long,
    val cpuPercentOneCore: Double,
    val pssStartKiB: Long,
    val pssEndKiB: Long,
    val rssStartKiB: Long?,
    val rssEndKiB: Long?,
    val peakRssKiB: Long?,
    val totalRamMiB: Long,
    val availableRamStartMiB: Long,
    val availableRamEndMiB: Long,
)

/** A one-shot local-only sample. It never schedules background polling. */
internal object ResourceUsageSampler {
    suspend fun capture(context: Context, durationMs: Long = DEFAULT_SAMPLE_MS): ResourceUsageSample {
        require(durationMs in MIN_SAMPLE_MS..MAX_SAMPLE_MS) { "Resource sample duration is outside the supported range." }
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: throw IllegalStateException("System memory information is unavailable.")
            val pssStartKiB = Debug.getPss()
            val rssStartKiB = readProcessMemory().rssKiB
            val ramStartMiB = readSystemMemory(activityManager)

            // Keep the memory queries outside the CPU measurement window.
            val wallStartMs = SystemClock.elapsedRealtime()
            val cpuStartMs = Process.getElapsedCpuTime()
            delay(durationMs)
            val cpuDeltaMs = (Process.getElapsedCpuTime() - cpuStartMs).coerceAtLeast(0L)
            val wallDeltaMs = (SystemClock.elapsedRealtime() - wallStartMs).coerceAtLeast(1L)

            val pssEndKiB = Debug.getPss()
            val processMemoryEnd = readProcessMemory()
            val ramEndMiB = readSystemMemory(activityManager)

            ResourceUsageSample(
                durationMs = wallDeltaMs,
                cpuPercentOneCore = cpuPercentOneCore(cpuDeltaMs, wallDeltaMs),
                pssStartKiB = pssStartKiB,
                pssEndKiB = pssEndKiB,
                rssStartKiB = rssStartKiB,
                rssEndKiB = processMemoryEnd.rssKiB,
                peakRssKiB = processMemoryEnd.peakRssKiB,
                totalRamMiB = ramStartMiB.first,
                availableRamStartMiB = ramStartMiB.second,
                availableRamEndMiB = ramEndMiB.second,
            )
        }
    }

    internal fun cpuPercentOneCore(cpuMs: Long, wallMs: Long): Double {
        if (cpuMs <= 0L || wallMs <= 0L) return 0.0
        return cpuMs * 100.0 / wallMs
    }

    private fun readSystemMemory(activityManager: ActivityManager): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return (info.totalMem / MIB).coerceAtLeast(0L) to (info.availMem / MIB).coerceAtLeast(0L)
    }

    private data class ProcessMemory(val rssKiB: Long?, val peakRssKiB: Long?)

    private fun readProcessMemory(): ProcessMemory = runCatching {
        var rss: Long? = null
        var peak: Long? = null
        File("/proc/self/status").useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("VmRSS:") -> rss = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                    line.startsWith("VmHWM:") -> peak = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                }
            }
        }
        ProcessMemory(rss, peak)
    }.getOrDefault(ProcessMemory(null, null))

    private const val DEFAULT_SAMPLE_MS = 5_000L
    private const val MIN_SAMPLE_MS = 1_000L
    private const val MAX_SAMPLE_MS = 30_000L
    private const val MIB = 1024L * 1024L
}
