package com.lowtube.android.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceUsageSamplerAcceptanceTest {
    @Test
    fun capturesProcessAndSystemMemoryOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = ResourceUsageSampler.capture(context, durationMs = 1_000L)
        Log.i(
            TAG,
            "durationMs=${sample.durationMs} cpuOneCorePercent=${sample.cpuPercentOneCore} " +
                "pssKiB=${sample.pssStartKiB}->${sample.pssEndKiB} " +
                "rssKiB=${sample.rssStartKiB}->${sample.rssEndKiB} peakRssKiB=${sample.peakRssKiB} " +
                "systemRamMiB=${sample.totalRamMiB} availableRamMiB=${sample.availableRamStartMiB}->${sample.availableRamEndMiB}",
        )

        assertTrue("Sample duration must be positive", sample.durationMs >= 1_000L)
        assertTrue("CPU usage must be finite and non-negative", sample.cpuPercentOneCore.isFinite() && sample.cpuPercentOneCore >= 0.0)
        assertTrue("PSS readings must be positive", sample.pssStartKiB > 0L && sample.pssEndKiB > 0L)
        assertTrue("Total system RAM must be positive", sample.totalRamMiB > 0L)
        assertTrue("Available RAM readings must be non-negative", sample.availableRamStartMiB >= 0L && sample.availableRamEndMiB >= 0L)
        assertTrue("RSS readings must either both be available or both absent", (sample.rssStartKiB == null) == (sample.rssEndKiB == null))
    }

    private companion object {
        const val TAG = "LowTubeResourceTest"
    }
}
