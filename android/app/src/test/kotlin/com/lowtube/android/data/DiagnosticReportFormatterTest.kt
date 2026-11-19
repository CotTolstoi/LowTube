package com.lowtube.android.data

import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import com.lowtube.android.playback.PlaybackFailureSnapshot
import com.lowtube.android.playback.VideoDecoderSnapshot
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportFormatterTest {
    @Test
    fun reportContainsOnlyAllowlistedRuntimeAndResourceFields() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportSnapshot(
                appVersion = "0.1.0-android-dev (1)",
                androidRelease = "13",
                androidApi = 33,
                deviceModel = "Pixel Test",
                decoder = VideoDecoderSnapshot("c2.android.avc.decoder", "avc1.64001F", PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE),
                buffer = PlaybackBufferPreferences(60, 64, 4, 3),
                resourceSample = ResourceUsageSample(5_000, 0.5, 80_000, 82_000, 100_000, 102_000, 110_000, 4096, 2000, 1990),
            ),
        )

        assertTrue(report.contains("Android: 13 (API 33)"))
        assertTrue(report.contains("Hardware decoder: hardware (reported by Android)"))
        assertTrue(report.contains("LowTube CPU: 0.5% of one core"))
        assertTrue(report.contains("80,000 → 82,000 KiB") || report.contains("80000 → 82000 KiB"))
        assertTrue(report.contains("excludes account details"))
        assertFalse(report.contains("@"))
        assertFalse(report.contains("https://"))
    }

    @Test
    fun reportWithoutDecoderOrResourceSampleClearlyShowsMissingMeasurements() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportSnapshot(
                appVersion = "dev",
                androidRelease = "6.0",
                androidApi = 23,
                deviceModel = "API23 AVD",
                decoder = VideoDecoderSnapshot(),
                buffer = PlaybackBufferPreferences(),
                resourceSample = null,
            ),
        )

        assertTrue(report.contains("Video decoder: not observed"))
        assertTrue(report.contains("Hardware decoder: unknown"))
        assertTrue(report.contains("Resource sample: not taken"))
    }

    @Test
    fun reportIncludesAggregateDownloadStatusesWithoutDownloadContent() {
        val counts = DownloadJobCounts.from(
            listOf(
                downloadRecord(DownloadStatus.PENDING, "Private video title", "https://www.youtube.com/watch?v=AbCdEfGhI12"),
                downloadRecord(DownloadStatus.RUNNING, "Another title", "content://private/path"),
                downloadRecord(DownloadStatus.FAILED, "Third title", "file:///private/video.mp4"),
            ),
        )
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportSnapshot(
                appVersion = "dev",
                androidRelease = "13",
                androidApi = 33,
                deviceModel = "Test",
                decoder = VideoDecoderSnapshot(),
                buffer = PlaybackBufferPreferences(),
                resourceSample = null,
                downloadJobs = counts,
            ),
        )

        assertTrue(report.contains("Download jobs: 1 pending, 1 running"))
        assertTrue(report.contains("1 failed"))
        assertFalse(report.contains("Private video title"))
        assertFalse(report.contains("AbCdEfGhI12"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("file:///"))
    }

    @Test
    fun reportIncludesOnlyPlaybackErrorCodeAndHttpStatus() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportSnapshot(
                appVersion = "dev",
                androidRelease = "13",
                androidApi = 33,
                deviceModel = "Test",
                decoder = VideoDecoderSnapshot(),
                buffer = PlaybackBufferPreferences(),
                resourceSample = null,
                playbackFailure = PlaybackFailureSnapshot("ERROR_CODE_IO_BAD_HTTP_STATUS", 403),
            ),
        )

        assertTrue(report.contains("Last playback error: ERROR_CODE_IO_BAD_HTTP_STATUS, HTTP 403"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("watch?v="))
    }

    private fun downloadRecord(status: Int, title: String, uri: String) = DownloadRecord(
        id = 12,
        title = title,
        status = status,
        reason = "private diagnostic reason",
        downloadedBytes = 16,
        totalBytes = 64,
        localUri = uri,
        mimeType = "video/mp4",
    )
}
