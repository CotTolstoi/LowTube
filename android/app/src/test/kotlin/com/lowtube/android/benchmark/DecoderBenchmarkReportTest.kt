package com.lowtube.android.benchmark

import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderBenchmarkReportTest {
    @Test
    fun reportIncludesMeasuredProfilesAndExplicitlyOmitsSourceIdentity() {
        val report = benchmarkReport(
            listOf(
                result(AndroidDecoderProfile.DEFAULT, "c2.mtk.avc.decoder", "OK"),
                result(AndroidDecoderProfile.SOFTWARE_PREFERRED, "c2.android.avc.decoder", "SLOW_OR_STALLED"),
            ),
        )

        assertTrue(report.startsWith("LowTube Android decoder benchmark"))
        assertTrue(report.contains("maximum clip endpoint 120 s"))
        assertTrue(report.contains("Run 1: profile=DEFAULT"))
        assertTrue(report.contains("Run 2: profile=SOFTWARE_PREFERRED"))
        assertTrue(report.contains("actual_video_decoder=c2.mtk.avc.decoder"))
        assertTrue(report.contains("actual_video_decoder=c2.android.avc.decoder"))
        assertTrue(report.contains("hardware_status=hardware"))
        assertTrue(report.contains("hardware_status=software"))
        assertTrue(report.contains("av_sync=unavailable"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("/storage/"))
        assertFalse(report.contains("private-video-title"))
    }

    private fun result(
        profile: AndroidDecoderProfile,
        decoder: String,
        status: String,
    ) = AndroidDecoderBenchmarkResult(
        profile = profile,
        status = status,
        decoderName = decoder,
        hardwareStatus = if (decoder.startsWith("c2.mtk")) {
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE
        } else {
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE
        },
        inputFormat = "avc1.42C00A",
        frameRate = 12.0,
        sampleCount = 7,
        cpuMeanPercentOneCore = 24.0,
        cpuPeakPercentOneCore = 35.0,
        pssPeakMiB = 86.0,
        rssPeakMiB = 125.0,
        availableRamMiB = 1_500.0,
        processedFrames = 119,
        droppedFrames = 0,
        audioUnderruns = 0,
        frameProcessingOffsetMaxMs = 43.0,
        mediaAdvanceSeconds = 7.9,
        wallSeconds = 8.1,
        firstFrameSeconds = 0.4,
        avSync = "unavailable",
    )
}
