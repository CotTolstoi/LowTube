package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceUsageSamplerTest {
    @Test
    fun reportsCpuAsPercentageOfOneCore() {
        assertEquals(50.0, ResourceUsageSampler.cpuPercentOneCore(cpuMs = 250L, wallMs = 500L), 0.0)
        assertEquals(125.0, ResourceUsageSampler.cpuPercentOneCore(cpuMs = 1_250L, wallMs = 1_000L), 0.0)
    }

    @Test
    fun reportsZeroForUnavailableCpuDeltaOrWallTime() {
        assertEquals(0.0, ResourceUsageSampler.cpuPercentOneCore(cpuMs = 10L, wallMs = 0L), 0.0)
        assertEquals(0.0, ResourceUsageSampler.cpuPercentOneCore(cpuMs = -1L, wallMs = 500L), 0.0)
    }
}
