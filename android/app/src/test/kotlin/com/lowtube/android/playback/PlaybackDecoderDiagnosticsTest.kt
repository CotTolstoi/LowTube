package com.lowtube.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDecoderDiagnosticsTest {
    @Test
    fun androidVersionsWithoutHardwareFlagRemainUnknown() {
        assertEquals(
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN,
            PlaybackDecoderDiagnostics.hardwareStatus(23, true),
        )
        assertEquals(
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN,
            PlaybackDecoderDiagnostics.hardwareStatus(23, false),
        )
    }

    @Test
    fun androidVersionsWithHardwareFlagReportTheSystemResult() {
        assertEquals(
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE,
            PlaybackDecoderDiagnostics.hardwareStatus(33, true),
        )
        assertEquals(
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE,
            PlaybackDecoderDiagnostics.hardwareStatus(33, false),
        )
        assertEquals(
            PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN,
            PlaybackDecoderDiagnostics.hardwareStatus(33, null),
        )
    }
}
