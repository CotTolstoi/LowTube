package com.lowtube.android.data

import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import com.lowtube.android.playback.VideoDecoderSnapshot
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
)

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
