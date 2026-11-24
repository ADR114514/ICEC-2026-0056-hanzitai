package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.CsvSchema
import com.mobilelab.powertrace.data.CsvText
import com.mobilelab.powertrace.data.PowerResolver
import com.mobilelab.powertrace.data.SessionSample
import com.mobilelab.powertrace.data.SummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerRecoveryTest {
    private fun point(index: Int, minute: Int, charge: Int? = null, energy: Long? = null, pct: Int = 50) =
        SessionSample(index, (index - 1) * 300L, 1_730_000_000_000L + minute * 60_000L,
            batteryLevelPct = pct, batteryVoltageMv = 4000, currentNowUa = -2100,
            chargeCounterUah = charge, energyCounterNwh = energy)

    private fun roundTrip(vararg rows: SessionSample): List<SessionSample> = CsvSchema.parseSessionCsv(
        CsvText.headerLine(CsvSchema.SESSION_HEADER) + rows.joinToString("") {
            CsvText.dataLine(CsvSchema.sessionRow("PT-test", ConditionId.GAME, it))
        },
    )

    @Test fun computedPowerAndProvenanceSurviveCsvRoundTrip() {
        val a = point(1, 0, 3_000_000)
        val b = point(2, 6, 2_975_000).copy(
            computedPowerW = 1.0, powerSource = "charge_counter", powerWindowMs = 360_000L,
        )
        val parsed = roundTrip(a, b)
        assertEquals(1.0, parsed.last().powerW!!, 1e-9)
        assertEquals("charge_counter", parsed.last().powerSource)
        assertEquals(360_000L, parsed.last().powerWindowMs)
        val summary = SummaryCalculator.summarize(ConditionId.GAME, 'D', parsed, 30.0)
        assertEquals(1.0, summary.avgPowerW!!, 1e-9)
        assertEquals(0.1, summary.energyWh!!, 1e-9)
        assertEquals(6.0, summary.powerWindowMin!!, 1e-9)
        assertEquals("charge_counter", summary.powerMethod)
    }

    @Test fun energyCounterWorksEvenIfVoltageIsMissingAndDoesNotDoubleCountCurrent() {
        val a = point(1, 0, energy = 10_000_000_000L)
        val b = point(2, 5, energy = 10_000_000_000L)
        val c = point(3, 10, energy = 9_750_000_000L)
        val rows = listOf(a, b, c).map { it.copy(batteryVoltageMv = null) }
        val summary = SummaryCalculator.summarize(ConditionId.GAME, 'D', rows, 30.0)
        assertEquals("energy_counter", summary.powerMethod)
        assertEquals(1.5, summary.avgPowerW!!, 1e-9)
        assertEquals(0.25, summary.energyWh!!, 1e-9)
        val mixed = SummaryCalculator.summarize(ConditionId.GAME, 'D', listOf(a, b, c).map {
            it.copy(currentNowUa = -500_000)
        }, 30.0)
        assertEquals(0.25, mixed.energyWh!!, 1e-9)
        assertEquals(10.0, mixed.powerWindowMin!!, 1e-9)
    }

    @Test fun aShortPauseDiscardsLegacyCrossBoundaryPowerAndCurrentIntegration() {
        val a = point(1, 0, 3_000_000).copy(note = "power_paused_ms=0")
        val b = point(2, 5, 2_950_000, pct = 48).copy(
            note = "power_paused_ms=1000", computedPowerW = 40.0,
            powerSource = "charge_counter", powerWindowMs = 300_000L,
        )
        val parsed = roundTrip(a, b)
        assertNull(PowerResolver.resolve(parsed).last().powerW)
        val summary = SummaryCalculator.summarize(ConditionId.GAME, 'D', parsed, 30.0, batteryCapacityMah = 5400.0)
        assertNull(summary.avgPowerW)
        assertNull(summary.peakPowerW)
        assertNull(summary.energyWh)
        val current = SummaryCalculator.summarize(ConditionId.GAME, 'D', parsed.map {
            it.copy(currentNowUa = -500_000)
        }, 30.0)
        assertNull(current.energyWh)
    }

    @Test fun chargingAndOldPercentagePointsAreNotPowerObservations() {
        val a = point(1, 0, 3_000_000)
        val b = point(2, 5, 2_975_000).copy(charging = true, currentNowUa = 500_000)
        val c = point(3, 10, 2_950_000, pct = 49).copy(computedPowerW = 1.0, powerSource = "battery_level_window")
        val summary = SummaryCalculator.summarize(ConditionId.GAME, 'D', roundTrip(a, b, c), 30.0, batteryCapacityMah = 5400.0)
        assertNull(summary.avgPowerW)
        assertNull(summary.energyWh)
        assertEquals("unavailable", summary.powerMethod)
    }

    @Test fun noConfiguredCapacityDoesNotAssumeOnePlusBattery() {
        val a = point(1, 0)
        val b = point(2, 5, pct = 48)
        val summary = SummaryCalculator.summarize(ConditionId.GAME, 'D', roundTrip(a, b), 30.0)
        assertNull(summary.avgPowerW)
        assertNull(summary.peakPowerW)
    }
}
