package com.mobilelab.chargeaudit.model

/** 页面路由。 */
enum class Screen { HOME, RECORD, RESULTS, SETTINGS }

enum class EndReason(val label: String) {
    DEPLETED("确认充电宝耗尽"),
    PHONE_FULL("手机充满 / 低负载停机"),
    DISCONNECTED("断线 / 拔出"),
    OTHER("主动停止 / 原因不明"),
}

enum class DepletionEvidence(val exportValue: String) {
    STOP_SAMPLE("stop_sample_recorded"),
    REPORTED_ONLY("reported_without_stop_sample"),
}

/** 仅保留用户核对过的铭牌/设备参数；未知项不填示例值。 */
data class Nameplate(
    val model: String = "",
    val ratedEnergyWh: Double? = null,
    val ratedOutputMah: Double? = null,
    val ratedOutputV: Double? = null,
    val cellTypicalMah: Double? = null,
    val cellRatedMah: Double? = null,
    val phoneCapacityMah: Double? = null,
    val phoneNominalV: Double? = null,
    val testerModel: String = "",
    val ratedOutputCurrentA: Double? = null,
    val ratedInput: String? = null,
    val ratedOutput: String? = null,
) {
    /** 额定 USB 输出容量按额定电压折算后 / 电芯铭牌能量，不是芯片效率。 */
    val ratedOutputToLabelRatio: Double?
        get() {
            val wh = ratedEnergyWh?.takeIf { it.isFinite() && it > 0 } ?: return null
            val mah = ratedOutputMah ?: return null
            val volts = ratedOutputV ?: return null
            return (mah / 1000.0 * volts / wh).takeIf { it.isFinite() && it > 0 }
        }
}

/** Transcribed from the CP11QC nameplate. No phone or USB-meter defaults. */
object NameplatePresets {
    val huaweiCp11qc = Nameplate(
        model = "华为 CP11QC",
        cellTypicalMah = 10_000.0,
        cellRatedMah = 9_500.0,
        ratedEnergyWh = 34.49,
        ratedOutputMah = 6_200.0,
        ratedOutputV = 5.0,
        ratedOutputCurrentA = 2.0,
        ratedInput = "5 V / 9 V ⎓ 2 A（最大）",
        ratedOutput = "5 V / 9 V ⎓ 2 A（最大）",
    )

    fun matchesHuaweiCp11qc(plate: Nameplate): Boolean =
        plate.copy(phoneCapacityMah = null, phoneNominalV = null, testerModel = "") == huaweiCp11qc

    /** Extra bank-only specifications must not be carried to a changed custom bank. */
    fun updateBank(known: Nameplate, model: String, energyWh: Double?, capacityMah: Double?, voltageV: Double?): Nameplate =
        if (model == known.model && energyWh == known.ratedEnergyWh &&
            capacityMah == known.ratedOutputMah && voltageV == known.ratedOutputV) {
            known
        } else {
            known.copy(
                model = model, ratedEnergyWh = energyWh, ratedOutputMah = capacityMah, ratedOutputV = voltageV,
                cellTypicalMah = null, cellRatedMah = null, ratedOutputCurrentA = null,
                ratedInput = null, ratedOutput = null,
            )
        }
}

/** 一个手工采样点；null 表示未读到，0 表示仪表实际读到 0。 */
data class AuditPoint(
    val index: Int,
    val timestampMs: Long,
    val elapsedMin: Double,
    val usbVoltageV: Double?,
    val usbCurrentA: Double?,
    val testerWhCum: Double? = null,
    val phonePct: Int? = null,
    val phoneVoltageMv: Int? = null,
    val phoneChargeUah: Long? = null,
    val phoneTempC: Double? = null,
    val charging: Boolean? = null,
    val note: String = "",
) {
    val powerW: Double?
        get() {
            val volts = usbVoltageV ?: return null
            val amps = usbCurrentA ?: return null
            return (volts * amps).takeIf { it.isFinite() && volts >= 0 && amps >= 0 && (volts > 0 || amps == 0.0) }
        }
}

/** 会话内有数据的能量与相对于铭牌的有符号差值。 */
data class ChargeSummary(
    val points: Int,
    val durationH: Double,
    val avgPowerW: Double?,
    val peakPowerW: Double?,
    val eOutWh: Double?,
    val outputMethod: String,
    val outputCoveredH: Double,
    val outputComplete: Boolean,
    val counterDecreased: Boolean,
    val fullDischarge: Boolean,
    val ePhoneWh: Double?,
    val phoneEnergyMethod: String,
    val etaOutput: Double?,
    val etaCharge: Double?,
    val etaTotal: Double?,
    val equivOutputMah: Double?,
    val ratedOutputToLabelRatio: Double?,
    val labelMinusOutputWh: Double?,
    val outputMinusPhoneWh: Double?,
    val labelMinusPhoneWh: Double?,
    val startPct: Int?,
    val endPct: Int?,
    val outOfRangeCurrentPoints: Int = 0,
)
