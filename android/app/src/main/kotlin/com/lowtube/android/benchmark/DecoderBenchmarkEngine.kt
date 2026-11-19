package com.lowtube.android.benchmark

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class AndroidDecoderProfile {
    DEFAULT,
    SOFTWARE_PREFERRED,
    HARDWARE_ONLY,
}

data class AndroidDecoderBenchmarkResult(
    val profile: AndroidDecoderProfile,
    val status: String,
    val decoderName: String,
    val hardwareStatus: Int,
    val inputFormat: String,
    val frameRate: Double,
    val sampleCount: Int,
    val cpuMeanPercentOneCore: Double,
    val cpuPeakPercentOneCore: Double,
    val pssPeakMiB: Double,
    val rssPeakMiB: Double?,
    val availableRamMiB: Double?,
    val processedFrames: Long,
    val droppedFrames: Long,
    val audioUnderruns: Long,
    val frameProcessingOffsetMaxMs: Double?,
    val mediaAdvanceSeconds: Double,
    val wallSeconds: Double,
    val firstFrameSeconds: Double?,
    val avSync: String,
) {
    fun asReportLine(): String = listOf(
        "profile=$profile",
        "status=$status",
        "actual_video_decoder=${decoderName.ifBlank { "unknown" }}",
        "hardware_status=${hardwareLabel(hardwareStatus)}",
        "input_format=${inputFormat.ifBlank { "unknown" }}",
        "fps=${if (frameRate > 0.0) "%.3f".format(java.util.Locale.ROOT, frameRate) else "unknown"}",
        "samples=$sampleCount",
        "cpu_mean_percent_one_core=${"%.2f".format(java.util.Locale.ROOT, cpuMeanPercentOneCore)}",
        "cpu_peak_percent_one_core=${"%.2f".format(java.util.Locale.ROOT, cpuPeakPercentOneCore)}",
        "pss_peak_mib=${"%.1f".format(java.util.Locale.ROOT, pssPeakMiB)}",
        "rss_peak_mib=${rssPeakMiB?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "unavailable"}",
        "available_ram_mib=${availableRamMiB?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "unavailable"}",
        "video_frame_processing_samples=$processedFrames",
        "dropped_video_frames=$droppedFrames",
        "audio_underruns=$audioUnderruns",
        "max_frame_batch_mean_processing_offset_ms=${frameProcessingOffsetMaxMs?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "unavailable"}",
        "media_advance_seconds=${"%.3f".format(java.util.Locale.ROOT, mediaAdvanceSeconds)}",
        "measured_wall_seconds=${"%.3f".format(java.util.Locale.ROOT, wallSeconds)}",
        "first_frame_seconds=${firstFrameSeconds?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "unavailable"}",
        "av_sync=$avSync",
    ).joinToString("; ")

    companion object {
        private fun hardwareLabel(status: Int): String = when (status) {
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE -> "hardware"
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE -> "software"
            else -> "unknown"
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
object DecoderBenchmarkEngine {
    private const val MAX_MEDIA_DURATION_MS = 120_000L
    private const val FIRST_FRAME_TIMEOUT_MS = 30_000L
    private const val MAX_WALL_DURATION_MS = 180_000L
    private const val WARMUP_MS = 2_000L
    private const val RESOURCE_SAMPLE_PERIOD_MS = 1_000L

    fun isHardwareProfileAvailable(androidApi: Int = Build.VERSION.SDK_INT): Boolean =
        androidApi >= Build.VERSION_CODES.Q

    fun hasHardwareVideoDecoder(context: Context, uri: Uri): Boolean {
        if (!isHardwareProfileAvailable()) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val videoMime = (0 until extractor.trackCount)
                .mapNotNull { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
                ?: return false
            MediaCodecSelector.DEFAULT.getDecoderInfos(videoMime, false, false).any { it.hardwareAccelerated }
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    fun createPlayer(context: Context, profile: AndroidDecoderProfile): ExoPlayer {
        require(profile != AndroidDecoderProfile.HARDWARE_ONLY || isHardwareProfileAvailable()) {
            "Hardware decoder classification is unavailable before Android 10; this profile is intentionally disabled."
        }
        val selector = when (profile) {
            AndroidDecoderProfile.DEFAULT -> MediaCodecSelector.DEFAULT
            AndroidDecoderProfile.SOFTWARE_PREFERRED -> MediaCodecSelector.PREFER_SOFTWARE
            AndroidDecoderProfile.HARDWARE_ONLY -> hardwareOnlySelector
        }
        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(false)
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
    }

    suspend fun run(
        context: Context,
        player: ExoPlayer,
        uri: Uri,
        profile: AndroidDecoderProfile,
        requestedSeconds: Int,
        onProgress: (Long, Long) -> Unit,
    ): AndroidDecoderBenchmarkResult {
        val mediaCapMs = requestedSeconds.coerceIn(10, 120).toLong() * 1_000L
        val videoDecoder = AtomicReference("")
        val inputFormat = AtomicReference("")
        val fpsBits = AtomicLong(java.lang.Double.doubleToRawLongBits(0.0))
        val hardwareStatus = AtomicLong(PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN.toLong())
        val droppedFrames = AtomicLong(0L)
        val processedFrames = AtomicLong(0L)
        val audioUnderruns = AtomicLong(0L)
        val maxFrameOffsetUs = AtomicLong(0L)
        val firstFrameElapsedMs = AtomicLong(-1L)
        val playbackError = AtomicReference<PlaybackException?>(null)
        val appContext = context.applicationContext
        val codecHardwareFlags = hardwareFlags()
        val listener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder.set(decoderName.take(256))
                val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codecHardwareFlags[decoderName] else null
                hardwareStatus.set(PlaybackDecoderDiagnostics.hardwareStatus(Build.VERSION.SDK_INT, actual).toLong())
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                inputFormat.set((format.codecs?.takeIf(String::isNotBlank) ?: format.sampleMimeType.orEmpty()).take(128))
                if (format.frameRate.isFinite() && format.frameRate > 0f) {
                    fpsBits.set(java.lang.Double.doubleToRawLongBits(format.frameRate.toDouble()))
                }
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                firstFrameElapsedMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrameCount: Int, elapsedMs: Long) {
                droppedFrames.addAndGet(droppedFrameCount.toLong().coerceAtLeast(0L))
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                if (frameCount > 0) updateMaxAbs(maxFrameOffsetUs, totalProcessingOffsetUs / frameCount)
                processedFrames.addAndGet(frameCount.toLong().coerceAtLeast(0L))
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                audioUnderruns.incrementAndGet()
            }
        }
        val playerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError.set(error)
            }
        }
        val requestedEndMs = min(mediaCapMs, MAX_MEDIA_DURATION_MS)
        val wallStart = SystemClock.elapsedRealtime()
        player.addAnalyticsListener(listener)
        player.addListener(playerListener)
        try {
            val item = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(requestedEndMs)
                        .build(),
                )
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.play()

            while (firstFrameElapsedMs.get() < 0L && playbackError.get() == null) {
                if (SystemClock.elapsedRealtime() - wallStart >= FIRST_FRAME_TIMEOUT_MS) {
                    throw IllegalStateException("No video frame rendered within the startup time limit.")
                }
                delay(100)
            }
            playbackError.get()?.let { throw it }
            val firstFrameTime = firstFrameElapsedMs.get()
            val warmupDeadline = min(wallStart + MAX_WALL_DURATION_MS, SystemClock.elapsedRealtime() + WARMUP_MS)
            while (SystemClock.elapsedRealtime() < warmupDeadline && player.currentPosition < requestedEndMs) {
                playbackError.get()?.let { throw it }
                delay(100)
            }

            // Keep playback metrics on the same interval as CPU, wall-time, and media-time measurements.
            droppedFrames.set(0L)
            processedFrames.set(0L)
            audioUnderruns.set(0L)
            maxFrameOffsetUs.set(0L)
            val measurementMediaStart = player.currentPosition
            val measurementWallStart = SystemClock.elapsedRealtime()
            var previousWall = measurementWallStart
            var previousCpu = Process.getElapsedCpuTime()
            var cpuWeighted = 0.0
            var cpuWeightMs = 0L
            var cpuPeak = 0.0
            var peakPssMiB = 0.0
            var peakRssMiB: Double? = null
            var availableRamMiB: Double? = null
            var sampleCount = 0
            var status = "OK"
            val memoryInfo = ActivityManager.MemoryInfo()
            val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

            while (player.currentPosition < requestedEndMs && player.playbackState != Player.STATE_ENDED) {
                val elapsed = SystemClock.elapsedRealtime() - measurementWallStart
                if (elapsed >= MAX_WALL_DURATION_MS) {
                    status = "WATCHDOG_TIMEOUT"
                    break
                }
                playbackError.get()?.let { throw it }
                delay(RESOURCE_SAMPLE_PERIOD_MS)
                val nowWall = SystemClock.elapsedRealtime()
                val nowCpu = Process.getElapsedCpuTime()
                val intervalWall = nowWall - previousWall
                val intervalCpu = nowCpu - previousCpu
                if (intervalWall > 0L) {
                    val percent = (intervalCpu.toDouble() / intervalWall) * 100.0
                    val maximumProcessPercent = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 100.0
                    val boundedCpu = percent.coerceIn(0.0, maximumProcessPercent)
                    cpuWeighted += boundedCpu * intervalWall
                    cpuWeightMs += intervalWall
                    cpuPeak = max(cpuPeak, boundedCpu)
                    sampleCount++
                }
                previousWall = nowWall
                previousCpu = nowCpu

                val processMemory = Debug.MemoryInfo()
                Debug.getMemoryInfo(processMemory)
                peakPssMiB = max(peakPssMiB, processMemory.totalPss / 1024.0)
                val rss = readRssMiB()
                if (rss != null) peakRssMiB = max(peakRssMiB ?: 0.0, rss)
                if (activityManager != null) {
                    activityManager.getMemoryInfo(memoryInfo)
                    availableRamMiB = memoryInfo.availMem / (1024.0 * 1024.0)
                }
                onProgress((player.currentPosition - measurementMediaStart).coerceAtLeast(0L), requestedEndMs - measurementMediaStart)
            }
            val measurementWallEnd = SystemClock.elapsedRealtime()
            val advanceMs = (player.currentPosition - measurementMediaStart).coerceAtLeast(0L)
            val wallMs = (measurementWallEnd - measurementWallStart).coerceAtLeast(0L)
            if (playbackError.get() != null) status = "PLAYBACK_ERROR"
            else if (player.playbackState == Player.STATE_ENDED && player.currentPosition < requestedEndMs - 1_000L) status = "SOURCE_ENDED_EARLY"
            else if (sampleCount < 3 || wallMs == 0L) status = "INSUFFICIENT_DATA"
            else if (advanceMs < wallMs * 0.95 || advanceMs > wallMs * 1.10) status = "SLOW_OR_STALLED"

            val fps = java.lang.Double.longBitsToDouble(fpsBits.get()).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            if (status == "OK" && fps <= 0.0) status = "UNVERIFIED_FPS"
            else if (status == "OK" && hasExcessiveDroppedFrameRatio(
                    droppedFrameCount = droppedFrames.get(),
                    frameProcessingSampleCount = processedFrames.get(),
                    frameRate = fps,
                    mediaAdvanceMs = advanceMs,
                )
            ) {
                status = "FRAME_DROPS"
            }

            val firstFrameSeconds = ((firstFrameTime - wallStart).coerceAtLeast(0L)) / 1_000.0
            val frameOffset = maxFrameOffsetUs.get().takeIf { it > 0L }?.div(1_000.0)
            return AndroidDecoderBenchmarkResult(
                profile = profile,
                status = status,
                decoderName = videoDecoder.get(),
                hardwareStatus = hardwareStatus.get().toInt(),
                inputFormat = inputFormat.get(),
                frameRate = fps,
                sampleCount = sampleCount,
                cpuMeanPercentOneCore = if (cpuWeightMs > 0L) cpuWeighted / cpuWeightMs else 0.0,
                cpuPeakPercentOneCore = cpuPeak,
                pssPeakMiB = peakPssMiB,
                rssPeakMiB = peakRssMiB,
                availableRamMiB = availableRamMiB,
                processedFrames = processedFrames.get(),
                droppedFrames = droppedFrames.get(),
                audioUnderruns = audioUnderruns.get(),
                frameProcessingOffsetMaxMs = frameOffset,
                mediaAdvanceSeconds = advanceMs / 1_000.0,
                wallSeconds = wallMs / 1_000.0,
                firstFrameSeconds = firstFrameSeconds,
                avSync = "unavailable",
            )
        } finally {
            player.removeAnalyticsListener(listener)
            player.removeListener(playerListener)
            player.pause()
        }
    }

    private val hardwareOnlySelector = object : MediaCodecSelector {
        override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            return if (mimeType.startsWith("video/")) available.filter { it.hardwareAccelerated } else available
        }
    }

    private fun hardwareFlags(): Map<String, Boolean> = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) emptyMap() else {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associate { it.name to it.isHardwareAccelerated }
        }.getOrDefault(emptyMap())
    }

    private fun readRssMiB(): Double? = runCatching {
        val line = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
            ?: return null
        val kib = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: return null
        kib / 1024.0
    }.getOrNull()

    internal fun hasExcessiveDroppedFrameRatio(
        droppedFrameCount: Long,
        frameProcessingSampleCount: Long,
        frameRate: Double,
        mediaAdvanceMs: Long,
    ): Boolean {
        if (droppedFrameCount <= 0L) return false
        val observedFrameCount = frameProcessingSampleCount.toDouble()
        val denominator = if (observedFrameCount > 0.0) {
            observedFrameCount
        } else {
            frameRate * mediaAdvanceMs.coerceAtLeast(0L) / 1_000.0
        }
        return denominator > 0.0 && droppedFrameCount / denominator > 0.01
    }

    private fun updateMaxAbs(target: AtomicLong, value: Long) {
        val magnitude = if (value == Long.MIN_VALUE) Long.MAX_VALUE else abs(value)
        while (true) {
            val current = target.get()
            if (magnitude <= current || target.compareAndSet(current, magnitude)) return
        }
    }
}
