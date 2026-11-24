package com.mobilelab.chargeaudit.data

import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.ChargeSummary
import com.mobilelab.chargeaudit.model.EndReason
import com.mobilelab.chargeaudit.model.DepletionEvidence

/** 样本的导出表示。保留输入精度；缺项为 CSV 空单元格或 JSON null。 */
internal object AuditExport {
    const val CSV_HEADER =
        "point_index,elapsed_min,timestamp_iso,usb_voltage_v,usb_current_a,usb_power_w," +
            "phone_battery_pct,phone_voltage_mv,phone_charge_counter_uah,phone_battery_temp_c,charging,note,tester_wh_cum"

    fun csvRow(point: AuditPoint, timestampIso: String): String = listOf(
        point.index.toString(),
        number(point.elapsedMin),
        timestampIso,
        number(point.usbVoltageV),
        number(point.usbCurrentA),
        number(point.powerW),
        point.phonePct?.toString().orEmpty(),
        point.phoneVoltageMv?.toString().orEmpty(),
        point.phoneChargeUah?.toString().orEmpty(),
        number(point.phoneTempC),
        point.charging?.let { if (it) "1" else "0" }.orEmpty(),
        csvCell(point.note),
        number(point.testerWhCum),
    ).joinToString(",")

    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.toString().orEmpty()
    private fun csvCell(value: String): String = if (value.any { it in ",\"\r\n" }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else value

    fun summaryFields(
        pointCount: Int,
        summary: ChargeSummary?,
        endReason: EndReason?,
        depletionEvidence: DepletionEvidence? = null,
        endReasonRecordedAt: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "points" to pointCount,
            "end_reason" to endReason?.name,
            "depletion_evidence" to depletionEvidence?.exportValue,
            "end_reason_recorded_at" to endReasonRecordedAt,
            "full_discharge_confirmed" to (summary?.fullDischarge ?: false),
            "duration_h" to summary?.durationH,
            "avg_power_w" to summary?.avgPowerW,
            "peak_power_w" to summary?.peakPowerW,
            "e_out_wh" to summary?.eOutWh,
            "output_energy_method" to (summary?.outputMethod ?: "unavailable"),
            "output_covered_h" to summary?.outputCoveredH,
            "output_complete" to (summary?.outputComplete ?: false),
            "out_of_range_current_points" to summary?.outOfRangeCurrentPoints,
            "tester_counter_decreased" to summary?.counterDecreased,
            "e_phone_wh" to summary?.ePhoneWh,
            "phone_energy_method" to (summary?.phoneEnergyMethod ?: "unavailable"),
            "eta_output" to summary?.etaOutput,
            "eta_charge" to summary?.etaCharge,
            "eta_total" to summary?.etaTotal,
            "equiv_output_mah" to summary?.equivOutputMah,
            "rated_output_to_label_ratio" to summary?.ratedOutputToLabelRatio,
            "label_minus_output_wh" to summary?.labelMinusOutputWh,
            "output_minus_phone_wh" to summary?.outputMinusPhoneWh,
            "label_minus_phone_wh" to summary?.labelMinusPhoneWh,
        ).mapValues { (_, value) -> if (value is Double && !value.isFinite()) null else value }
}
