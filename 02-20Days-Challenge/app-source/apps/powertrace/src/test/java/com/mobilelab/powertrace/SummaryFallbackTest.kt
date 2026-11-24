package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.SessionSample
import com.mobilelab.powertrace.data.SummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryFallbackTest {

    private fun sample(index: Int, level: Int, voltageMv: Int = 4000) = SessionSample(
        sampleIndex = index,
        elapsedSec = index * 300L,
        timestampMs = 1_000_000L + index * 300_000L,
        batteryLevelPct = level,
        batteryVoltageMv = voltageMv,
        batteryTempC = 33.0,
        currentNowUa = -500,
        computedPowerW = null,
        charging = false,
    )

    @Test
    fun missingPowerFallsBackToBatteryLevel() {
        val samples = listOf(sample(0, 50), sample(1, 49), sample(2, 48))
        val summary = SummaryCalculator.summarize(
            condition = ConditionId.IDLE,
            block = 'A',
            samples = samples,
            durationMin = 30.0,
            batteryCapacityMah = 5400.0,
        )
        assertNotNull(summary.avgPowerW)
        assertNotNull(summary.energyWh)
        // 二个 5 分钟间隔，不以工况总时长 30 min 作分母。
        assertEquals(2.592, summary.avgPowerW!!, 1e-6)
        assertEquals(10.0, summary.powerWindowMin!!, 1e-6)
        assertEquals("battery_level_estimate", summary.powerMethod)
        assertEquals(null, summary.peakPowerW)
        assertEquals(0.432, summary.energyWh!!, 1e-6)
        assertTrue(summary.notes.contains("回退估算"))
    }
}
