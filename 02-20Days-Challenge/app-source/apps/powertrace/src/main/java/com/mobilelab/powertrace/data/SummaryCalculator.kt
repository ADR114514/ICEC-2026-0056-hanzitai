package com.mobilelab.powertrace.data

/**
 * 工况汇总（数据字典 1.3）的纯计算。
 *
 * 口径说明：
 *  - avg_power_w 按有效实测时间窗加权，peak_power_w 仅为最大采样点/窗口值；
 *  - energy_wh 仅累加实际覆盖且不重叠的区间，不外推到完整工况；
 *  - level_drop_pct = start - end（放电为正）；
 *  - temp_rise_c = temp_end - temp_start；
 *  - thermal_rise_events = thermal_status 相邻点抬升次数；
 *  - net_rx_mb / net_tx_mb = Σ 平均 kbps × 300 s 换算成 MB。
 */
data class ConditionSummary(
    val condition: ConditionId,
    val block: Char,
    val durationMin: Double,
    val startLevelPct: Int?,
    val endLevelPct: Int?,
    val levelDropPct: Int?,
    val avgPowerW: Double?,
    val peakPowerW: Double?,
    val energyWh: Double?,
    val avgCurrentMa: Double?,
    val tempStartC: Double?,
    val tempEndC: Double?,
    val tempRiseC: Double?,
    val thermalMax: Int?,
    val thermalRiseEvents: Int?,
    val avgCpuFreqMhz: Int?,
    val avgScreenBrightnessPct: Int?,
    val netRxMb: Double?,
    val netTxMb: Double?,
    val notes: String,
    val sampleCount: Int,
    val samples: List<SessionSample> = emptyList(),
    val powerMethod: String = "unavailable",
    val powerWindowMin: Double? = null,
) {
    fun toRow(): List<Any?> = listOf(
        condition.id,
        condition.label,
        Fmt.dec1(durationMin),
        Fmt.int(startLevelPct),
        Fmt.int(endLevelPct),
        Fmt.int(levelDropPct),
        Fmt.dec3(avgPowerW),
        Fmt.dec3(peakPowerW),
        Fmt.dec3(energyWh),
        Fmt.dec1(avgCurrentMa),
        Fmt.dec1(tempStartC),
        Fmt.dec1(tempEndC),
        Fmt.dec1(tempRiseC),
        Fmt.int(thermalMax),
        Fmt.int(thermalRiseEvents),
        Fmt.int(avgCpuFreqMhz),
        Fmt.int(avgScreenBrightnessPct),
        Fmt.dec2(netRxMb),
        Fmt.dec2(netTxMb),
        notes,
    )
}

object SummaryCalculator {

    /** 由真实落盘的 6 行采样点计算一个工况的汇总；调用方负责给出实际时长。 */
    fun summarize(
        condition: ConditionId,
        block: Char,
        samples: List<SessionSample>,
        durationMin: Double,
        extraNotes: String = "",
        batteryCapacityMah: Double = 0.0,
        legacyPaused: Boolean = false,
    ): ConditionSummary {
        val ordered = PowerResolver.resolve(samples, legacyPaused)
        val currentsMa = ordered.filter { !it.charging }.map { s ->
            s.currentNowUa?.takeIf { PowerMath.isCurrentPlausible(it) }?.let { kotlin.math.abs(it.toLong()) / 1000.0 }
        }
        val levels = ordered.map { it.batteryLevelPct }
        val temps = ordered.map { it.batteryTempC }
        val thermals = ordered.map { it.thermalStatus }
        val cpuFreqs = ordered.map { it.cpuFreqAvgMhz }
        val brightness = ordered.map { it.screenBrightnessPct }
        val rx = ordered.map { it.netRxKbps }
        val tx = ordered.map { it.netTxKbps }
        val first = ordered.firstOrNull()
        val last = ordered.lastOrNull()
        val startLevel = levels.firstOrNull()
        val endLevel = levels.lastOrNull()
        val levelDrop = if (startLevel != null && endLevel != null) startLevel - endLevel else null
        val spanMs = if (first != null && last != null) last.timestampMs - first.timestampMs else 0L
        val hasCharging = ordered.any { it.charging }
        fun boundary(a: SessionSample, b: SessionSample) = PowerResolver.isBoundary(a, b, legacyPaused)
        val hasPause = ordered.zipWithNext().any { (a, b) -> boundary(a, b) }

        data class Interval(val start: Long, val end: Long, val watts: Double, val method: String)
        val used = mutableListOf<Interval>()
        fun add(interval: Interval) {
            if (used.none { it.start < interval.end && interval.start < it.end }) used.add(interval)
        }
        // Prefer counter deltas to sparse current points, and avoid double-counting
        // where two sensor windows overlap. No extrapolation outside observed times.
        val counters = ordered.mapNotNull { point ->
            val dt = point.powerWindowMs ?: return@mapNotNull null
            val watts = point.powerW ?: return@mapNotNull null
            val start = point.timestampMs - dt
            val method = point.powerSource ?: return@mapNotNull null
            if (first != null && dt > 0L && start >= first.timestampMs) Interval(start, point.timestampMs, watts, method) else null
        }
        counters.sortedByDescending { it.end - it.start }.forEach { add(it) }
        for ((a, b) in ordered.zipWithNext()) {
            if (a.charging || b.charging || boundary(a, b)) continue
            // Use two hardware current observations only in gaps without counter coverage.
            val pa = PowerMath.instantPowerW(a.batteryVoltageMv, a.currentNowUa) ?: continue
            val pb = PowerMath.instantPowerW(b.batteryVoltageMv, b.currentNowUa) ?: continue
            add(Interval(a.timestampMs, b.timestampMs, (pa + pb) / 2.0, "current_now"))
        }
        val observedMs = used.sumOf { it.end - it.start }
        val observedEnergy = used.sumOf { it.watts * (it.end - it.start) / 3_600_000.0 }
        val usedMethods = used.map { it.method }.toSet()
        val validPowers = ordered.mapNotNull { it.powerW }
        var method = when {
            usedMethods.size == 1 -> usedMethods.first()
            usedMethods.size > 1 -> "mixed"
            validPowers.isNotEmpty() -> ordered.firstOrNull { it.powerW != null }?.powerSource ?: "saved_unknown"
            else -> "unavailable"
        }
        var windowMin: Double? = observedMs.takeIf { it > 0 }?.div(60000.0)
        var avgPowerW = if (observedMs > 0) observedEnergy / (observedMs / 3_600_000.0) else PowerMath.avg(validPowers)
        var peakPowerW = PowerMath.max(validPowers)
        var energyWh: Double? = observedEnergy.takeIf { observedMs > 0 }
        // SOC is a coarse estimate, never a calibrated wattmeter or per-point peak.
        val canEstimate = validPowers.isEmpty() && !hasCharging && !hasPause && spanMs >= 60_000L &&
            levelDrop != null && levelDrop > 0 && batteryCapacityMah.isFinite() && batteryCapacityMah in 1000.0..20000.0 &&
            ordered.zipWithNext().all { (a, b) -> a.batteryLevelPct != null && b.batteryLevelPct != null && b.batteryLevelPct <= a.batteryLevelPct }
        if (canEstimate) {
            val averageMv = ordered.mapNotNull { it.batteryVoltageMv?.takeIf { v -> v in 2500..20000 } }.average()
            val voltageV = averageMv / 1000.0
            if (voltageV.isFinite() && voltageV > 0) {
                energyWh = levelDrop!! / 100.0 * batteryCapacityMah / 1000.0 * voltageV
                avgPowerW = energyWh / (spanMs / 3_600_000.0)
                peakPowerW = null
                method = "battery_level_estimate"
                windowMin = spanMs / 60000.0
            }
        }
        val autoNotes = buildList {
            if (ordered.isNotEmpty() && ordered.size < SamplingConfig.SAMPLES_PER_CONDITION) add("仅采到 " + ordered.size + "/6 点")
            val late = ordered.count { it.note.contains("late_sample") }
            if (late > 0) add("迟到样本 " + late + " 个")
            if (hasCharging) add("采集期间充电：相关区间已排除，不代表纯放电功耗")
            if (method == "battery_level_estimate") {
                add("电量%×已配置容量回退估算；不是准确实测，不能推算峰值")
                add("battery_capacity_mah=" + batteryCapacityMah + "（汇总时设置）")
                add("1个百分点差值误差相对本次掉电约为 " + Fmt.dec1(100.0 / levelDrop!!) + "%，另有容量/SOC非线性误差")
            }
            if (method == "unavailable") add("功率不可用：无有效计数器差分/可信电流；电量估算需配置容量且有非充电掉电窗口")
            if (hasPause) add("暂停或时间轴不连续：不跨边界估算")
            add("power_method=" + method)
            windowMin?.let { add("power_window_min=" + Fmt.dec3(it)) }
            if (windowMin != null && windowMin < durationMin - 0.01) add("能量仅覆盖有效观测窗口，不外推到整个工况")
        }
        val note = (listOf(extraNotes) + autoNotes).filter { it.isNotBlank() }.joinToString("; ")

        return ConditionSummary(
            condition = condition,
            block = block,
            durationMin = durationMin,
            startLevelPct = startLevel,
            endLevelPct = endLevel,
            levelDropPct = levelDrop,
            avgPowerW = avgPowerW,
            peakPowerW = peakPowerW,
            energyWh = energyWh,
            avgCurrentMa = PowerMath.avg(currentsMa),
            tempStartC = temps.firstOrNull(),
            tempEndC = temps.lastOrNull(),
            tempRiseC = PowerMath.diffDouble(temps.firstOrNull(), temps.lastOrNull()),
            thermalMax = PowerMath.maxInt(thermals),
            thermalRiseEvents = PowerMath.thermalRiseEvents(thermals),
            avgCpuFreqMhz = PowerMath.avgInt(cpuFreqs),
            avgScreenBrightnessPct = PowerMath.avgInt(brightness),
            netRxMb = PowerMath.megabytesFromKbps(rx),
            netTxMb = PowerMath.megabytesFromKbps(tx),
            notes = note,
            sampleCount = ordered.size,
            samples = ordered,
            powerMethod = method,
            powerWindowMin = windowMin,
        )
    }
}
