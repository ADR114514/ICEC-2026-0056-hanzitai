package com.mobilelab.powertrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.BuildConfig
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SystemAccess
import java.io.File
import java.time.Instant

/** 设置页：采样参数（固定）、原始序列开关、权限入口、关于/版本。 */
@Composable
fun SettingsScreen(
    access: SystemAccess.Snapshot,
    campaign: CampaignState?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var rawEnabled by remember { mutableStateOf(PowerTraceController.isRawSeriesEnabled()) }
    var guardEnabled by remember { mutableStateOf(PowerTraceController.isStandbyGuardEnabled()) }
    val running = campaign?.status == com.mobilelab.powertrace.data.CampaignStatus.RUNNING

    LabScaffold(
        title = "设置",
        subtitle = "采样参数、授权入口与版本信息",
        onBack = onBack,
    ) {
        ScreenScroll {
            SectionCard(title = "采样参数") {
                KeyValueRow("采样间隔", "${SamplingConfig.INTERVAL_SEC} s（固定，不可修改）")
                KeyValueRow("单工况时长", "${SamplingConfig.CONDITION_DURATION_SEC / 60} min")
                KeyValueRow("单工况采样点", "${SamplingConfig.SAMPLES_PER_CONDITION} 个")
                KeyValueRow("迟到判定阈值", "${SamplingConfig.LATE_TOLERANCE_MS / 1000} s")
                KeyValueRow("采样持锁上限", "${SamplingConfig.WAKE_LOCK_TIMEOUT_MS / 1000} s")
                Spacer(Modifier.height(6.dp))
                HintText("固定 300 s 间隔是数据契约的一部分，分析管线与历史数据都依赖该口径。")
            }

            SectionCard(title = "原始高频序列（可选）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "每 ${SamplingConfig.RAW_INTERVAL_SEC} s 记录 raw_<工况>.csv",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rawEnabled,
                        onCheckedChange = { checked ->
                            rawEnabled = checked
                            PowerTraceController.setRawSeriesEnabled(checked)
                        },
                        enabled = !running,
                    )
                }
                HintText(
                    if (running) {
                        "批次采集中不可修改；新批次生效。"
                    } else {
                        "默认关闭（30 min ≈ 360 行，写入压力较大）。开启后仅在设备醒着时记录，作为补充数据。"
                    },
                )
            }

            SectionCard(title = "待机准点守护（实验性）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "IDLE 工况持有部分唤醒锁",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextPrimary,
                        )
                        HintText("仅当系统把 5 min 采样点延后（迟到>90 s）时开启；开启后待机功耗含数十 mW CPU 驻留开销，报告中需说明。优先尝试关闭系统睡眠待机优化并允许应用后台活动。")
                    }
                    Switch(
                        checked = guardEnabled,
                        onCheckedChange = { checked ->
                            guardEnabled = checked
                            PowerTraceController.setStandbyGuardEnabled(checked)
                        },
                    )
                }
            }

            SectionCard(title = "授权入口") {
                AccessRow(
                    title = "通知权限",
                    granted = access.notifications,
                    hint = "前台服务进度显示在通知栏",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.openNotificationSettings(context) },
                )
                AccessRow(
                    title = "电池优化豁免",
                    granted = access.batteryUnrestricted,
                    hint = "避免 Doze 延迟采样（待机工况尤其重要）",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestBatteryExemption(context) },
                )
                AccessRow(
                    title = "精确闹钟",
                    granted = access.exactAlarm,
                    hint = "setExactAndAllowWhileIdle 唤醒采样",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.requestExactAlarmAccess(context) },
                )
                AccessRow(
                    title = "使用情况访问",
                    granted = access.usageStats,
                    hint = "可选：记录 foreground_pkg 前台包名",
                    actionLabel = "去设置",
                    onAction = { SystemAccess.openUsageAccessSettings(context) },
                )
            }

            SectionCard(title = "数据与导出") {
                val root = PowerTraceController.experimentsRoot()
                KeyValueRow(
                    "数据根目录",
                    root?.let { "experiments/powertrace" } ?: "读取失败",
                )
                if (root != null) {
                    KeyValueRow("绝对路径", root.absolutePath)
                }
                campaign?.let { state ->
                    KeyValueRow("当前批次目录", File(state.dirPath).name)
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    "目录结构（数据字典 1.5）：manifest.json、session_<工况>.csv（6 行/工况）、" +
                        "campaign_<批次>.csv（24 行）、campaign_summary_<批次>.csv、可选 raw_<工况>.csv、session.log。",
                )
                HintText("CSV 为 UTF-8 + BOM + CRLF，Excel 与 pandas 可直接打开。导出/分享在结果页操作。")
            }


            SectionCard(title = "功率回退（仅工况平均估算）") {
                var capacityText by remember {
                    mutableStateOf(PowerTraceController.batteryCapacityMah().takeIf { it > 0 }?.toString().orEmpty())
                }
                var capacityMessage by remember { mutableStateOf<String?>(null) }
                OutlinedTextField(
                    value = capacityText,
                    onValueChange = { capacityText = it; capacityMessage = null },
                    label = { Text("手机电池容量 mAh（电量%回退估算用）") },
                    placeholder = { Text("未配置，请核对铭牌/官方规格后手填") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("保存容量", {
                    val capacity = capacityText.trim().toIntOrNull()
                    if (capacity != null && capacity in 1000..20000) {
                        PowerTraceController.setBatteryCapacityMah(capacity)
                        capacityMessage = "已保存 $capacity mAh；仅用于电量%回退估算，不代表已校准。"
                    } else {
                        capacityMessage = "未保存：请核对铭牌/官方规格，输入 1000–20000 mAh 的整数容量。"
                    }
                }, Modifier.fillMaxWidth())
                capacityMessage?.let { HintText(it) }
                HintText("不自动假定电池容量；请核对本机铭牌/官方规格后手动填写并保存。未配置容量时，不进行电量%功率估算。")
                HintText("系统电荷计、能量计及电流属性是否可用取决于具体设备与固件，不能仅凭品牌判断。" +
                    "逐点功率不可用时，可在满足放电与时间窗条件后，用实际首末电量差 × 设定容量与电压估算窗口平均功率；不能估算峰值。")
                HintText("电量以 1%（1 个百分点）分辨率报告时会有量化误差；误差的相对影响取决于窗口内掉电幅度，掉电越少，相对误差可能越大。" +
                    "容量设定、老化、电压与系统电量算法也会影响结果，不承诺统一准确率。未观察到掉电不等于 0 W；无有效估算时显示不可用。")
            }

            SectionCard(title = "功率诊断") {
                var diag by remember { mutableStateOf(readBatteryDiag(context)) }
                var diagnosticMessage by remember { mutableStateOf<String?>(null) }
                KeyValueRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                KeyValueRow("Android", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                KeyValueRow("读数时间（UTC）", Instant.ofEpochMilli(diag.observedAtMs).toString())
                KeyValueRow("电荷计", diag.chargeUah?.let { it.toString() + " µAh" } ?: "不可用")
                KeyValueRow("能量计", diag.energyNwh?.let { it.toString() + " nWh" } ?: "不可用")
                KeyValueRow("CURRENT_NOW", diag.currentUa?.let { it.toString() + " µA" } ?: "不可用")
                KeyValueRow("电池电压", diag.voltageMv?.let { it.toString() + " mV" } ?: "不可用")
                KeyValueRow("当前电量", diag.levelPct?.let { it.toString() + " %" } ?: "不可用")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("刷新读数", {
                        diag = readBatteryDiag(context)
                        diagnosticMessage = null
                    }, Modifier.weight(1f))
                    SecondaryButton("分享诊断", {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "PowerTrace 公有 API 电池诊断")
                            putExtra(Intent.EXTRA_TEXT, batteryDiagnosticText(diag))
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, "分享诊断")) }
                            .onFailure { diagnosticMessage = "无法打开分享面板，请刷新后重试。" }
                    }, Modifier.weight(1f))
                }
                diagnosticMessage?.let { HintText(it) }
                HintText("仅诊断 Android 公有 API 原始读数，单位标注按 API 定义；不推断厂商单位、不自动修正，也不代表功率已校准。分享包含上方设备、系统和电池读数，不含设备序列号。")
                HintText("以上是本次读取的系统属性快照，可点击刷新；单次计数或 CURRENT_NOW 为 0 不能证明功耗为 0。" +
                    "功率可来自电荷计/能量计差分，或有效电流 × 电压；是否可计算还取决于样本间隔、放电状态及读数变化。")
                HintText("差分至少需要两个有效样本，但第 2 点及以后也不保证有功率。缺失时显示不可用；条件满足后的电量%回退仅出现在工况平均估算中。")
            }

            SectionCard(title = "关于") {
                KeyValueRow("App", "功耗热测仪 / PowerTrace")
                KeyValueRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueRow("课题", "02 · 多工况电量与发热实测")
                KeyValueRow("包名", BuildConfig.APPLICATION_ID)
                KeyValueRow("目标设备", "Android 12+ (arm64-v8a)")
                Spacer(Modifier.height(6.dp))
                HintText(
                    "功率口径：电荷计或能量计差分表示真实采样时间窗内的平均功率；电流 × 电压表示采样点估算值。" +
                        "结果页标明来源与实际首末时间窗；电量% × 容量回退只估算窗口平均功率与能量，不作为峰值。" +
                        "数据不足、读数无效或充电等情况下，功率可能不可用；不把缺失点按 0 W 解释。",
                )
                HintText("数据契约：docs/DATA_DICTIONARY.md 第一节（唯一权威）。")
            }
        }
    }
}

private data class BatteryDiag(
    val observedAtMs: Long,
    val chargeUah: Long?,
    val energyNwh: Long?,
    val currentUa: Long?,
    val voltageMv: Int?,
    val levelRaw: Int?,
    val scaleRaw: Int?,
    val levelPct: Int?,
)

private fun batteryDiagnosticText(diag: BatteryDiag): String = buildString {
    appendLine("PowerTrace 公有 API 电池诊断")
    appendLine("time_utc=${Instant.ofEpochMilli(diag.observedAtMs)}")
    appendLine("manufacturer=${Build.MANUFACTURER}")
    appendLine("model=${Build.MODEL}")
    appendLine("android_release=${Build.VERSION.RELEASE}; sdk_int=${Build.VERSION.SDK_INT}")
    appendLine("charge_counter_raw=${diag.chargeUah ?: "不可用"}; API单位=µAh")
    appendLine("energy_counter_raw=${diag.energyNwh ?: "不可用"}; API单位=nWh")
    appendLine("current_now_raw=${diag.currentUa ?: "不可用"}; API单位=µA")
    appendLine("voltage_raw=${diag.voltageMv ?: "不可用"}; API单位=mV")
    appendLine("level_raw=${diag.levelRaw ?: "不可用"}; scale_raw=${diag.scaleRaw ?: "不可用"}")
    appendLine("level_pct=${diag.levelPct ?: "不可用"}")
    appendLine("仅 Android 公有 API 原始读数；不可用表示读取失败、缺失或 API 哨兵值。")
    appendLine("不猜测厂商单位、不自动修正；此快照不能校准功率，也不承诺准确率。")
}

/** 读一次系统电池属性，用于 Settings 里自检“为什么功率为空”。 */
private fun readBatteryDiag(context: Context): BatteryDiag {
    val manager = context.getSystemService(BatteryManager::class.java)
    fun prop(id: Int): Long? = runCatching { manager.getLongProperty(id) }
        .getOrNull()?.takeIf { it != Long.MIN_VALUE }
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    fun extra(name: String): Int? = battery?.getIntExtra(name, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
    val voltage = extra(BatteryManager.EXTRA_VOLTAGE)
    val level = extra(BatteryManager.EXTRA_LEVEL)
    val scale = extra(BatteryManager.EXTRA_SCALE)
    return BatteryDiag(
        observedAtMs = System.currentTimeMillis(),
        chargeUah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
        energyNwh = prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
        currentUa = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
        voltageMv = voltage,
        levelRaw = level,
        scaleRaw = scale,
        levelPct = if (level != null && level >= 0 && scale != null && scale > 0) level * 100 / scale else null,
    )
}
