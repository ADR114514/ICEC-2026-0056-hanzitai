package com.mobilelab.chargeaudit.math

import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.ChargeSummary
import com.mobilelab.chargeaudit.model.EndReason
import com.mobilelab.chargeaudit.model.DepletionEvidence
import com.mobilelab.chargeaudit.model.Nameplate

/** 纯计算逻辑：可在 JVM 单测里直接验证（不依赖 Android）。 */
object ChargeMath {
    private data class Output(val wh: Double?, val method: String, val coveredH: Double, val complete: Boolean)

    fun validateMeterReading(volts: Double?, amps: Double?, cumulativeWh: Double?): String? {
        if (volts != null && (!volts.isFinite() || volts < 0)) return "电压须为有限的非负数"
        if (amps != null && (!amps.isFinite() || amps < 0)) return "电流须为有限的非负数"
        if (cumulativeWh != null && (!cumulativeWh.isFinite() || cumulativeWh < 0)) return "累计 Wh 须为有限的非负数"
        if (volts == 0.0 && amps != null && amps > 0) return "0 V 不能与正电流同时记录，请核对仪表"
        if (volts != null && amps != null && !(volts * amps).isFinite()) return "计算功率超出有效范围"
        return null
    }

    fun canConfirmDepleted(points: List<AuditPoint>): Boolean {
        val last = points.lastOrNull() ?: return false
        return points.dropLast(1).any { (it.powerW ?: 0.0) > 0 } &&
            (last.usbVoltageV == null || last.usbVoltageV == 0.0) &&
            (last.usbCurrentA == null || last.usbCurrentA == 0.0)
    }

    fun depletionEvidence(points: List<AuditPoint>, reason: EndReason?): DepletionEvidence? = when {
        reason != EndReason.DEPLETED -> null
        canConfirmDepleted(points) -> DepletionEvidence.STOP_SAMPLE
        else -> DepletionEvidence.REPORTED_ONLY
    }

    private fun exceedsRating(point: AuditPoint, currentLimitA: Double?): Boolean =
        currentLimitA != null && currentLimitA > 0 && currentLimitA.isFinite() &&
            point.usbCurrentA?.let { it > currentLimitA } == true

    /** Do not interpolate across missing readings or readings above the configured nameplate limit. */
    private fun trapezoid(points: List<AuditPoint>, currentLimitA: Double? = null): Output {
        var total = 0.0
        var hours = 0.0
        var intervals = 0
        for ((first, last) in points.zipWithNext()) {
            if (exceedsRating(first, currentLimitA) || exceedsRating(last, currentLimitA)) continue
            val dtH = (last.elapsedMin - first.elapsedMin) / 60.0
            val p0 = first.powerW ?: continue
            val p1 = last.powerW ?: continue
            if (!dtH.isFinite() || dtH <= 0.0) continue
            total += (p0 / 2.0 + p1 / 2.0) * dtH
            hours += dtH
            intervals++
        }
        val wh = total.takeIf { intervals > 0 && it.isFinite() }
        return Output(wh, if (wh != null) "trapezoid" else "unavailable", hours, wh != null && intervals == points.size - 1)
    }

    fun trapezoidEnergyWh(points: List<AuditPoint>): Double? = trapezoid(points).wh

    /** 手机电池净入能量估算：电荷计正差表示电池电荷增加，负差表示净减少。 */
    fun phoneEnergyWh(points: List<AuditPoint>, capacityMah: Double?, nominalV: Double?): Pair<Double?, String> {
        if (points.size < 2) return null to "unavailable"
        val first = points.first()
        val last = points.last()
        val startQ = first.phoneChargeUah?.takeIf { it >= 0 }
        val endQ = last.phoneChargeUah?.takeIf { it >= 0 }
        if (startQ != null && endQ != null) {
            val deltaUah = endQ - startQ
            val voltageMv = points.mapNotNull { it.phoneVoltageMv?.takeIf { v -> v > 0 } }.average()
            if (voltageMv.isFinite()) return deltaUah.toDouble() * voltageMv / 1e9 to "charge_counter"
            if (nominalV != null && nominalV.isFinite() && nominalV > 0) {
                val wh = deltaUah / 1e6 * nominalV
                if (wh.isFinite()) return wh to "charge_counter_x_nominal_voltage"
            }
        }
        val startPct = first.phonePct?.takeIf { it in 0..100 }
        val endPct = last.phonePct?.takeIf { it in 0..100 }
        if (startPct != null && endPct != null && capacityMah != null && nominalV != null && capacityMah > 0 && nominalV > 0) {
            val wh = (endPct - startPct) / 100.0 * (capacityMah / 1000.0) * nominalV
            if (wh.isFinite()) return wh to "battery_pct_x_capacity"
        }
        return null to "unavailable"
    }

    fun summarize(
        points: List<AuditPoint>, nameplate: Nameplate, endReason: EndReason? = null, fullStartConfirmed: Boolean = false,
    ): ChargeSummary? {
        if (points.size < 2 || points.any { !it.elapsedMin.isFinite() }) return null
        val sorted = points.sortedBy { it.elapsedMin }
        if (sorted.zipWithNext().any { (a, b) -> b.elapsedMin <= a.elapsedMin }) return null
        val first = sorted.first()
        val last = sorted.last()
        val durationH = (last.elapsedMin - first.elapsedMin) / 60.0
        if (!durationH.isFinite()) return null

        val cumulative = sorted.mapNotNull { point -> point.testerWhCum?.takeIf { it.isFinite() && it >= 0 }?.let { point to it } }
        val counterDecreased = cumulative.zipWithNext().any { (a, b) -> b.second < a.second }
        val meter = if (cumulative.size >= 2 && !counterDecreased) {
            val (startPoint, startWh) = cumulative.first()
            val (endPoint, endWh) = cumulative.last()
            Output(endWh - startWh, "tester_cumulative_delta", (endPoint.elapsedMin - startPoint.elapsedMin) / 60.0,
                startPoint == first && endPoint == last)
        } else null
        val integrated = trapezoid(sorted, nameplate.ratedOutputCurrentA)
        val output = when {
            meter?.complete == true -> meter
            integrated.complete -> integrated
            meter != null && meter.coveredH >= integrated.coveredH -> meter
            else -> integrated
        }
        val eOut = output.wh
        val (ePhone, method) = phoneEnergyWh(sorted, nameplate.phoneCapacityMah, nameplate.phoneNominalV)
        val rated = nameplate.ratedEnergyWh?.takeIf { it.isFinite() && it > 0 }
        val ratedOutputV = nameplate.ratedOutputV?.takeIf { it.isFinite() && it > 0 }
        val fullDischarge = fullStartConfirmed && depletionEvidence(sorted, endReason) == DepletionEvidence.STOP_SAMPLE
        val completeOut = eOut?.takeIf { output.complete }
        val fullOut = completeOut?.takeIf { fullDischarge }
        fun ratio(numerator: Double?, denominator: Double?): Double? =
            if (numerator != null && denominator != null && denominator > 0) (numerator / denominator).takeIf { it.isFinite() } else null
        fun difference(a: Double?, b: Double?): Double? = if (a != null && b != null) (a - b).takeIf { it.isFinite() } else null
        return ChargeSummary(
            points = sorted.size,
            durationH = durationH,
            avgPowerW = ratio(eOut, output.coveredH),
            peakPowerW = sorted.filterNot { exceedsRating(it, nameplate.ratedOutputCurrentA) }.mapNotNull { it.powerW }.maxOrNull(),
            eOutWh = eOut,
            outputMethod = output.method,
            outputCoveredH = output.coveredH,
            outputComplete = output.complete,
            counterDecreased = counterDecreased,
            fullDischarge = fullDischarge,
            ePhoneWh = ePhone,
            phoneEnergyMethod = method,
            etaOutput = ratio(fullOut, rated),
            etaCharge = ratio(ePhone, completeOut),
            etaTotal = ratio(ePhone?.takeIf { fullOut != null }, rated),
            equivOutputMah = ratio(eOut, ratedOutputV)?.times(1000.0)?.takeIf { it.isFinite() },
            ratedOutputToLabelRatio = nameplate.ratedOutputToLabelRatio,
            labelMinusOutputWh = difference(rated, fullOut),
            outputMinusPhoneWh = difference(completeOut, ePhone),
            labelMinusPhoneWh = difference(rated, ePhone?.takeIf { fullOut != null }),
            startPct = first.phonePct,
            endPct = last.phonePct,
            outOfRangeCurrentPoints = sorted.count { exceedsRating(it, nameplate.ratedOutputCurrentA) },
        )
    }

    fun fmt(value: Double?, digits: Int = 2, unit: String = ""): String {
        if (value == null || !value.isFinite()) return "--"
        return when (digits) {
            0 -> "%.0f".format(java.util.Locale.US, value) + unit
            1 -> "%.1f".format(java.util.Locale.US, value) + unit
            3 -> "%.3f".format(java.util.Locale.US, value) + unit
            else -> "%.2f".format(java.util.Locale.US, value) + unit
        }
    }
}
