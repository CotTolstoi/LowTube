package com.lowtube.android.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderBenchmarkMetricsTest {
    @Test
    fun droppedFrameRatioUsesRendererSamplesFromTheMeasuredInterval() {
        assertFalse(
            DecoderBenchmarkEngine.hasExcessiveDroppedFrameRatio(
                droppedFrameCount = 1,
                frameProcessingSampleCount = 120,
                frameRate = 12.0,
                mediaAdvanceMs = 7_800,
            ),
        )
        assertTrue(
            DecoderBenchmarkEngine.hasExcessiveDroppedFrameRatio(
                droppedFrameCount = 2,
                frameProcessingSampleCount = 120,
                frameRate = 12.0,
                mediaAdvanceMs = 7_800,
            ),
        )
    }

    @Test
    fun droppedFrameRatioFallsBackToFrameRateWhenRendererSamplesAreUnavailable() {
        assertFalse(
            DecoderBenchmarkEngine.hasExcessiveDroppedFrameRatio(
                droppedFrameCount = 1,
                frameProcessingSampleCount = 0,
                frameRate = 12.0,
                mediaAdvanceMs = 10_000,
            ),
        )
        assertFalse(
            DecoderBenchmarkEngine.hasExcessiveDroppedFrameRatio(
                droppedFrameCount = 2,
                frameProcessingSampleCount = 0,
                frameRate = 0.0,
                mediaAdvanceMs = 8_000,
            ),
        )
    }
}
