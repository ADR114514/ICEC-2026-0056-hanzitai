package com.mobilelab.chargeaudit

import com.mobilelab.chargeaudit.data.AuditExport
import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.EndReason
import com.mobilelab.chargeaudit.model.DepletionEvidence
import com.mobilelab.chargeaudit.model.Nameplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeMathTest {
    private fun point(index: Int, minute: Double, voltage: Double? = 5.0, current: Double? = 2.0, pct: Int? = null) =
        AuditPoint(index, 1_000_000L + index * 600_000L, minute, voltage, current, phonePct = pct)

    private val plate = Nameplate(ratedEnergyWh = 10.0, ratedOutputV = 5.0)

    @Test
    fun phoneChargeCounterUsesEndMinusStartEvenWhenSocIsUnchanged() {
        val first = point(0, 0.0, pct = 20).copy(phoneChargeUah = 500_000, phoneVoltageMv = 3800)
        val last = point(1, 10.0, pct = 20).copy(phoneChargeUah = 700_000, phoneVoltageMv = 4200)
        val (wh, method) = ChargeMath.phoneEnergyWh(listOf(first, last), null, null)
        assertEquals(0.8, wh!!, 1e-9)
        assertEquals("charge_counter", method)
        val (netLoss, _) = ChargeMath.phoneEnergyWh(listOf(last, first), null, null)
        assertEquals(-0.8, netLoss!!, 1e-9)
    }

    @Test
    fun pctFallbackUsesOnlyStartAndEndReadings() {
        val points = listOf(point(0, 0.0, pct = 20), point(1, 10.0, pct = 40), point(2, 20.0, pct = 90))
        val (wh, method) = ChargeMath.phoneEnergyWh(points, 5400.0, 3.85)
        assertEquals(0.70 * 5.4 * 3.85, wh!!, 1e-9)
        assertEquals("battery_pct_x_capacity", method)
        assertNull(ChargeMath.phoneEnergyWh(points.dropLast(1) + point(2, 20.0), 5400.0, 3.85).first)
    }

    @Test
    fun cumulativeWhUsesNonZeroBaselineAndWorksWithMissingInstantaneousEndpoint() {
        val first = point(0, 0.0).copy(testerWhCum = 12.25)
        val middle = point(1, 10.0)
        val last = point(2, 20.0, null, null).copy(testerWhCum = 14.75)
        val summary = ChargeMath.summarize(listOf(first, middle, last), plate, EndReason.DEPLETED, true)!!
        assertEquals("tester_cumulative_delta", summary.outputMethod)
        assertEquals(2.5, summary.eOutWh!!, 1e-9)
        assertEquals(0.25, summary.etaOutput!!, 1e-9)
        assertEquals(7.5, summary.labelMinusOutputWh!!, 1e-9)
        assertTrue(summary.outputComplete)
        assertNull(summary.ePhoneWh)
    }

    @Test
    fun resetMeterFallsBackToTrapezoidAndAcceptsRealZero() {
        val points = listOf(point(0, 0.0).copy(testerWhCum = 10.0), point(1, 60.0, 0.0, 0.0).copy(testerWhCum = 0.1))
        assertNull(ChargeMath.validateMeterReading(0.0, 0.0, 0.1))
        assertTrue(ChargeMath.canConfirmDepleted(points))
        val summary = ChargeMath.summarize(points, plate, EndReason.DEPLETED, true)!!
        assertEquals("trapezoid", summary.outputMethod)
        assertTrue(summary.counterDecreased)
        assertEquals(5.0, summary.eOutWh!!, 1e-9)
        assertEquals(0.5, summary.etaOutput!!, 1e-9)
    }

    @Test
    fun missingTerminalReadingDoesNotBecomeZeroOrBridgeAnInterval() {
        val points = listOf(point(0, 0.0), point(1, 10.0), point(2, 20.0, null, null))
        assertTrue(ChargeMath.canConfirmDepleted(points))
        val summary = ChargeMath.summarize(points, plate, EndReason.DEPLETED, true)!!
        assertEquals(10.0 / 6.0, summary.eOutWh!!, 1e-9)
        assertEquals(1.0 / 6.0, summary.outputCoveredH, 1e-9)
        assertFalse(summary.outputComplete)
        assertNull(summary.etaOutput)
        assertNull(summary.labelMinusOutputWh)
    }

    @Test
    fun phoneFullOrDisconnectAndUnconfirmedStartDoNotQualifyForFullRunRatio() {
        val samples = listOf(point(0, 0.0), point(1, 60.0, 0.0, 0.0))
        for (reason in listOf(EndReason.PHONE_FULL, EndReason.DISCONNECTED, EndReason.OTHER, null)) {
            val summary = ChargeMath.summarize(samples, plate, reason, true)!!
            assertEquals(5.0, summary.eOutWh!!, 1e-9)
            assertFalse(summary.fullDischarge)
            assertNull(summary.etaOutput)
        }
        assertNull(ChargeMath.summarize(samples, plate, EndReason.DEPLETED)!!.etaOutput)
        assertFalse(ChargeMath.canConfirmDepleted(listOf(point(0, 0.0), point(1, 60.0, 5.0, 0.0))))
    }

    @Test
    fun negativeDifferencesAreNotClampedAndInvalidReadingsAreRejected() {
        val samples = listOf(point(0, 0.0, current = 6.0), point(1, 60.0, 0.0, 0.0))
        val summary = ChargeMath.summarize(samples, plate, EndReason.DEPLETED, true)!!
        assertEquals(-5.0, summary.labelMinusOutputWh!!, 1e-9)
        assertNotNull(ChargeMath.validateMeterReading(0.0, 1.0, null))
        assertNotNull(ChargeMath.validateMeterReading(5.0, 1.0, Double.NaN))
        assertNotNull(ChargeMath.validateMeterReading(Double.POSITIVE_INFINITY, 1.0, null))
        assertNotNull(ChargeMath.validateMeterReading(5.0, 1.0, -0.01))
    }

    @Test
    fun reportedDepletionSurvivesExportWithoutBecomingACompleteDischarge() {
        val points = listOf(point(0, 0.0), point(1, 10.0, 8.9, 1.23, pct = 96).copy(charging = true))
        val reason = EndReason.DEPLETED
        val evidence = ChargeMath.depletionEvidence(points, reason)
        assertEquals(DepletionEvidence.REPORTED_ONLY, evidence)
        val summary = ChargeMath.summarize(points, plate, reason, true)!!
        assertTrue(summary.outputComplete)
        assertFalse(summary.fullDischarge)
        assertNull(summary.etaOutput)
        assertNull(summary.etaTotal)
        val recordedAt = "2026-11-24T01:30:00+08:00"
        val exported = AuditExport.summaryFields(points.size, summary, reason, evidence, recordedAt)
        assertEquals("DEPLETED", exported["end_reason"])
        assertEquals("reported_without_stop_sample", exported["depletion_evidence"])
        assertEquals(recordedAt, exported["end_reason_recorded_at"])
        assertEquals(false, exported["full_discharge_confirmed"])
        assertNull(exported["eta_output"])
        assertTrue(AuditExport.csvRow(points.last(), "ts").contains(",8.9,1.23,"))
        assertNull(ChargeMath.depletionEvidence(points, EndReason.PHONE_FULL))
        val withStop = points + point(2, 20.0, 0.0, 0.0)
        assertEquals(DepletionEvidence.STOP_SAMPLE, ChargeMath.depletionEvidence(withStop, reason))
        assertTrue(ChargeMath.summarize(withStop, plate, reason, true)!!.fullDischarge)
    }

    @Test
    fun skipsBothIntervalsAroundOverLimitCurrentWithoutChangingRawExport() {
        val samples = listOf(point(0, 0.0), point(1, 10.0), point(2, 20.0, 8.7, 10.7),
            point(3, 30.0), point(4, 40.0, 0.0, 0.0))
        val summary = ChargeMath.summarize(samples, plate.copy(ratedOutputCurrentA = 2.0), EndReason.DEPLETED, true)!!
        assertEquals(1, summary.outOfRangeCurrentPoints)
        assertEquals(2.5, summary.eOutWh!!, 1e-9)
        assertEquals(20.0 / 60, summary.outputCoveredH, 1e-9)
        assertEquals(10.0, summary.peakPowerW!!, 1e-9)
        assertFalse(summary.outputComplete)
        assertNull(summary.etaOutput)
        assertTrue(AuditExport.csvRow(samples[2], "ts").contains(",8.7,10.7,"))
    }

    @Test
    fun exportPreservesCounterAndDiffersBetweenZeroAndBlankAndKeepsUnavailableSummaryFields() {
        val first = point(0, 0.0).copy(testerWhCum = 2.1234567)
        val missing = point(1, 60.0, null, null)
        val zero = point(1, 60.0, 0.0, 0.0)
        assertEquals("tester_wh_cum", AuditExport.CSV_HEADER.substringAfterLast(','))
        assertTrue(AuditExport.csvRow(first, "ts").endsWith(",2.1234567"))
        assertEquals(listOf("", "", ""), AuditExport.csvRow(missing, "ts").split(',').slice(3..5))
        assertEquals(listOf("0.0", "0.0", "0.0"), AuditExport.csvRow(zero, "ts").split(',').slice(3..5))
        val summary = ChargeMath.summarize(listOf(first, zero), plate, EndReason.DEPLETED, true)!!
        val exported = AuditExport.summaryFields(2, summary.copy(ePhoneWh = Double.NaN, avgPowerW = Double.POSITIVE_INFINITY), EndReason.DEPLETED)
        assertTrue(exported.containsKey("e_phone_wh"))
        assertNull(exported["e_phone_wh"])
        assertNull(exported["avg_power_w"])
        assertEquals(5.0, exported["e_out_wh"])
        assertEquals(1, AuditExport.summaryFields(1, null, null)["points"])
    }
}
