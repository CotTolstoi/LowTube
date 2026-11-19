package com.lowtube.android.data

import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import com.lowtube.android.playback.PlaybackFailureSnapshot
import com.lowtube.android.playback.VideoDecoderSnapshot
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import java.util.Locale

/** Explicit, user-copied troubleshooting data. It has no account, content, URL, or session fields. */
internal data class DiagnosticReportSnapshot(
    val appVersion: String,
    val androidRelease: String,
    val androidApi: Int,
    val deviceModel: String,
    val decoder: VideoDecoderSnapshot,
    val buffer: PlaybackBufferPreferences,
    val resourceSample: ResourceUsageSample?,
    val downloadJobs: DownloadJobCounts? = null,
    val playbackFailure: PlaybackFailureSnapshot = PlaybackFailureSnapshot(),
)

internal data class DownloadJobCounts(
    val pending: Int = 0,
    val running: Int = 0,
    val paused: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val unknown: Int = 0,
    val legacyScanTruncated: Boolean = false,
) {
    companion object {
        fun from(records: List<DownloadRecord>): DownloadJobCounts =
            fromCounts(records.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() })

        fun fromCounts(counts: Map<Int, Long>, legacyScanTruncated: Boolean = false): DownloadJobCounts = DownloadJobCounts(
            pending = count(counts, DownloadStatus.PENDING),
            running = count(counts, DownloadStatus.RUNNING),
            paused = count(counts, DownloadStatus.PAUSED),
            successful = count(counts, DownloadStatus.SUCCESSFUL),
            failed = count(counts, DownloadStatus.FAILED),
            cancelled = count(counts, DownloadStatus.CANCELLED),
            unknown = counts.filterKeys { it !in KNOWN_STATUSES }.values.sumOf { it.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
            legacyScanTruncated = legacyScanTruncated,
        )

        private fun count(counts: Map<Int, Long>, status: Int): Int =
            counts[status]?.coerceAtLeast(0L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0

        private val KNOWN_STATUSES = setOf(
            DownloadStatus.PENDING,
            DownloadStatus.RUNNING,
            DownloadStatus.PAUSED,
            DownloadStatus.SUCCESSFUL,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )
    }

    operator fun plus(other: DownloadJobCounts) = DownloadJobCounts(
        pending = safeAdd(pending, other.pending),
        running = safeAdd(running, other.running),
        paused = safeAdd(paused, other.paused),
        successful = safeAdd(successful, other.successful),
        failed = safeAdd(failed, other.failed),
        cancelled = safeAdd(cancelled, other.cancelled),
        unknown = safeAdd(unknown, other.unknown),
        legacyScanTruncated = legacyScanTruncated || other.legacyScanTruncated,
    )

    private fun safeAdd(first: Int, second: Int): Int =
        (first.toLong() + second.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal object DiagnosticReportFormatter {
    fun format(snapshot: DiagnosticReportSnapshot): String = buildString {
        appendLine("LowTube Android diagnostics")
        appendLine("App: ${safe(snapshot.appVersion)}")
        appendLine("Android: ${safe(snapshot.androidRelease)} (API ${snapshot.androidApi.coerceAtLeast(0)})")
        appendLine("Device model: ${safe(snapshot.deviceModel)}")
        appendLine("Video decoder: ${safe(snapshot.decoder.name).ifBlank { "not observed" }}")
        appendLine("Input format: ${safe(snapshot.decoder.inputFormat).ifBlank { "not observed" }}")
        appendLine("Hardware decoder: ${hardwareStatus(snapshot.decoder.hardwareStatus)}")
        appendLine(
            "Playback buffer: ${snapshot.buffer.maximumSeconds}s, " +
                "${snapshot.buffer.memoryLimitMiB} MiB target, ${snapshot.buffer.backBufferMiB} MiB back buffer, " +
                "${snapshot.buffer.startupSeconds}s startup",
        )
        if (snapshot.playbackFailure.errorCodeName.isBlank()) {
            appendLine("Last playback error: none recorded")
        } else {
            val status = snapshot.playbackFailure.httpStatusCode?.let { ", HTTP $it" }.orEmpty()
            appendLine("Last playback error: ${snapshot.playbackFailure.errorCodeName}$status")
        }
        val jobs = snapshot.downloadJobs
        if (jobs == null) {
            appendLine("Download jobs: unavailable")
        } else {
            appendLine(
                "Download jobs: ${jobs.pending.coerceAtLeast(0)} pending, ${jobs.running.coerceAtLeast(0)} running, " +
                    "${jobs.paused.coerceAtLeast(0)} paused, ${jobs.successful.coerceAtLeast(0)} successful, " +
                    "${jobs.failed.coerceAtLeast(0)} failed, ${jobs.cancelled.coerceAtLeast(0)} cancelled, " +
                    "${jobs.unknown.coerceAtLeast(0)} unknown",
            )
            if (jobs.legacyScanTruncated) appendLine("Download job counts: legacy records are partial (bounded scan)")
        }
        val sample = snapshot.resourceSample
        if (sample == null) {
            appendLine("Resource sample: not taken")
        } else {
            appendLine("Resource sample duration: ${sample.durationMs.coerceAtLeast(0)} ms")
            appendLine("LowTube CPU: ${sample.cpuPercentOneCore.coerceAtLeast(0.0).formatOneDecimal()}% of one core")
            appendLine("LowTube PSS: ${sample.pssStartKiB.coerceAtLeast(0)} → ${sample.pssEndKiB.coerceAtLeast(0)} KiB")
            appendLine(
                "LowTube RSS: ${sample.rssStartKiB?.coerceAtLeast(0)?.toString() ?: "unavailable"} → " +
                    "${sample.rssEndKiB?.coerceAtLeast(0)?.toString() ?: "unavailable"} KiB",
            )
            appendLine("LowTube peak RSS: ${sample.peakRssKiB?.coerceAtLeast(0)?.toString() ?: "unavailable"} KiB")
            appendLine(
                "Available system RAM: ${sample.availableRamStartMiB.coerceAtLeast(0)} → " +
                    "${sample.availableRamEndMiB.coerceAtLeast(0)} MiB of ${sample.totalRamMiB.coerceAtLeast(0)} MiB",
            )
        }
        appendLine("Privacy: this report excludes account details, video titles/IDs, URLs, search history, cookies, and tokens.")
    }

    private fun hardwareStatus(status: Int): String = when (status) {
        PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE -> "hardware (reported by Android)"
        PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE -> "software (reported by Android)"
        else -> "unknown"
    }

    private fun safe(value: String): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(MAX_FIELD_LENGTH)

    private fun Double.formatOneDecimal(): String = String.format(Locale.ROOT, "%.1f", this)

    private const val MAX_FIELD_LENGTH = 128
}
