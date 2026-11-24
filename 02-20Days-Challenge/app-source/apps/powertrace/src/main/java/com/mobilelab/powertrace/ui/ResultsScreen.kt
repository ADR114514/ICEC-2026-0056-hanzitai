package com.mobilelab.powertrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.ui.EmptyState
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.ConditionSummary
import com.mobilelab.powertrace.data.ExportManager
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.data.SamplingConfig
import com.mobilelab.powertrace.data.SessionSample
import com.mobilelab.powertrace.service.SamplingControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 结果页：4 工况汇总卡片 + 24 行合并表预览 + 导出/分享/逐文件导出 + 单工况补测。
 *
 * 汇总与批次 CSV 都从已落盘的 session CSV 重新计算，保证"数据可溯源"。
 */
@Composable
fun ResultsScreen(
    campaign: CampaignState?,
    onBack: () -> Unit,
    onRerunStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberFileExporter()

    var bundle by remember { mutableStateOf<ExportManager.ResultsBundle?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var rerunTarget by remember { mutableStateOf<ConditionId?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(campaign?.campaignId, refreshKey) {
        if (campaign == null) {
            bundle = null
            files = emptyList()
            return@LaunchedEffect
        }
        bundle = PowerTraceController.loadResults(writeDerived = true)
        files = withContext(Dispatchers.IO) { PowerTraceController.exportableFiles() }
    }

    if (campaign == null) {
        LabScaffold(title = "结果与导出", onBack = onBack) {
            EmptyState("暂无批次数据", "完成一次采集后可在这里查看 4 工况对比并导出")
        }
        return
    }

    val data = bundle
    val active = campaign.status == CampaignStatus.RUNNING || campaign.status == CampaignStatus.PAUSED

    LabScaffold(
        title = "结果与导出",
        subtitle = "${campaign.campaignId} · 会话 ${campaign.sessionId}",
        status = "${campaign.status.label} · ${campaign.totalSamplesDone} 行采样 · ${files.size} 个文件",
        onBack = onBack,
        bottomBar = {
            BottomActions {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(text = "返回", onClick = onBack)
                    PrimaryButton(
                        text = "一键分享全部 ZIP",
                        onClick = {
                            if (busy) return@PrimaryButton
                            busy = true
                            scope.launch {
                                val zip = PowerTraceController.zipBundle()
                                busy = false
                                if (zip == null) {
                                    message = "打包失败：目录不存在或无权限"
                                } else {
                                    files = withContext(Dispatchers.IO) { PowerTraceController.exportableFiles() }
                                    exporter.share(zip, "application/zip")
                                    message = "已生成 ZIP：${zip.name}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = busy,
                    )
                }
            }
        },
    ) {
        ScreenScroll {
            if (message != null) {
                Text(text = message!!, style = MaterialTheme.typography.bodySmall, color = LabColors.Success)
            }

            if (data == null) {
                SectionCard(title = "加载中") { HintText("正在从会话目录读取采样行…") }
            } else {
                SectionCard(title = "4 工况汇总") {
                    if (data.summaries.isEmpty()) {
                        HintText("暂无已完成的工况")
                    } else {
                        SummaryPowerChart(data.summaries)
                        Spacer(Modifier.height(6.dp))
                        KeyValueRow("工况数", "${data.summaries.size}")
                        KeyValueRow("采样行数", "${data.summaries.sumOf { it.sampleCount }} / ${data.summaries.size * SamplingConfig.SAMPLES_PER_CONDITION}")
                        KeyValueRow(
                            "批次 CSV 行数",
                            data.samplesByCondition.values.sumOf { it.size }.toString() + " 行（4 工况完成时应为 24 行）",
                        )
                        KeyValueRow("数据目录", "experiments/powertrace/${File(campaign.dirPath).name}")
                    }
                }

                data.summaries.forEach { summary ->
                    SummaryCard(
                        summary = summary,
                        canRerun = !active,
                        onRerun = { rerunTarget = summary.condition },
                    )
                }

                SectionCard(title = "24 行合并表预览（批次 CSV 结构）") {
                    HintText("列序：campaign_id, block, 以及会话 CSV 的 31 列（去掉 session_id）。导出文件包含全部列。")
                    Spacer(Modifier.height(6.dp))
                    HintText("逐点功率来源和窗口信息见 note；工况电量%估算不会填入逐点 power_W，也不能用于峰值。")
                    PreviewTable(data.state, data.samplesByCondition)
                }

                SectionCard(title = "导出") {
                    HintText("目录结构符合数据字典 1.5；导出前会自动刷新派生 CSV 与 manifest.json。")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = "批次 CSV",
                            onClick = {
                                val file = data.campaignCsv
                                if (file == null) message = "批次 CSV 尚未生成" else exporter.saveAs(file, "text/csv")
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "汇总 CSV",
                            onClick = {
                                val file = data.summaryCsv
                                if (file == null) message = "汇总 CSV 尚未生成" else exporter.saveAs(file, "text/csv")
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = "分享批次 CSV",
                            onClick = {
                                data.campaignCsv?.let { exporter.share(it, "text/csv") }
                                    ?: run { message = "批次 CSV 尚未生成" }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "分享汇总 CSV",
                            onClick = {
                                data.summaryCsv?.let { exporter.share(it, "text/csv") }
                                    ?: run { message = "汇总 CSV 尚未生成" }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinkButton(
                        text = "重新读取并刷新派生文件",
                        onClick = { refreshKey++ },
                    )
                }


                SectionCard(title = "删除数据（不可恢复）", accent = LabColors.Error) {
                    HintText("删除错误批次：会删除本批次会话目录（CSV / session.log / manifest）并清空当前批次状态，之后可重新新建批次测量。")
                    if (active) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill("采集中/已暂停：请先在采集页暂停或放弃批次，再删除", PillTone.Warning)
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = "删除本批次数据",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !active && !busy,
                    )
                }
                SectionCard(title = "逐文件导出（${files.size}）") {
                    if (files.isEmpty()) {
                        HintText("目录中暂无文件")
                    } else {
                        files.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LabColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = "${file.length()} B",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LabColors.TextSecondary,
                                    )
                                }
                                LinkButton(
                                    text = "分享",
                                    onClick = { exporter.share(file, mimeOf(file)) },
                                )
                                LinkButton(
                                    text = "另存",
                                    onClick = { exporter.saveAs(file, mimeOf(file)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除本批次数据？") },
            text = {
                Text(
                    "将永久删除批次 " + campaign.campaignId + " 的会话目录与 " + campaign.totalSamplesDone +
                        " 行已采数据，操作不可撤销。建议先导出 ZIP 备份。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        val ok = PowerTraceController.deleteCampaignData()
                        busy = false
                        if (ok) {
                            onBack()
                        } else {
                            message = "删除失败：批次不存在或仍在采集中"
                        }
                    }
                }) { Text("确认删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (rerunTarget != null) {
        val target = rerunTarget!!
        AlertDialog(
            onDismissRequest = { rerunTarget = null },
            title = { Text("补测「${target.label}」？") },
            text = {
                Text(
                    "将新建一个单工况批次（30 min，6 个采样点），原批次数据不受影响。" +
                        "补测批次的 manifest 会记录 rerunOf = ${campaign.campaignId}。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rerunTarget = null
                    scope.launch {
                        PowerTraceController.createRerunCampaign(target, notes = "")
                        SamplingControl.start(context)
                        onRerunStarted()
                    }
                }) { Text("开始补测") }
            },
            dismissButton = { TextButton(onClick = { rerunTarget = null }) { Text("取消") } },
        )
    }
}

/** 只给有效功率画条；缺失值保留文字状态，不能冒充零功率。 */
@Composable
private fun SummaryPowerChart(summaries: List<ConditionSummary>) {
    val maxPower = summaries.mapNotNull { summary ->
        summary.avgPowerW?.takeIf { it.isFinite() && it >= 0.0 }
    }.maxOrNull()
    summaries.forEach { summary ->
        val power = summary.avgPowerW?.takeIf { it.isFinite() && it >= 0.0 }
        KeyValueRow(summary.condition.label, formatPowerWithUnit(power, "W"))
        if (power == null) {
            HintText("功率不可用：不绘制为 0 W，也不参与功率对比。")
        } else {
            HintText(powerMethodLabel(summary.powerMethod))
            val fraction = if (maxPower != null && maxPower > 0.0) {
                (power / maxPower).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(LabColors.TextSecondary.copy(alpha = 0.12f))) {
                if (fraction > 0f) {
                    Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(
                        if (summary.powerMethod == "battery_level_estimate") LabColors.Tertiary else LabColors.Primary,
                    ))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    HintText("对比工况平均功率；各工况来源与时间窗可能不同。电量%估算单独标注，不代表瞬时功率。显示保留 4 位有效数字，不代表测量准确率。")
}

private fun powerMethodLabel(method: String): String = when (method) {
    "charge_counter" -> "电荷计差分（窗口平均）"
    "energy_counter" -> "能量计差分（窗口平均）"
    "current_now" -> "系统电流 × 电压（采样点估算）"
    "saved_unknown" -> "已保存功率（历史来源未记录）"
    "mixed" -> "多种来源混合（逐点来源见 note）"
    "battery_level_estimate" -> "电量% × 设定容量与电压（窗口平均估算）"
    "unavailable" -> "不可用"
    else -> "未知来源（$method）"
}

/** 用有效数字保留小功率，避免把有效的非零值四舍五入为 0。 */
private fun formatPowerAvailable(value: Double?): String = when {
    value == null || !value.isFinite() -> "不可用"
    value == 0.0 -> "0"
    else -> String.format(Locale.US, "%.4g", value)
}

private fun formatPowerWithUnit(value: Double?, unit: String): String =
    if (value == null || !value.isFinite()) "不可用" else "${formatPowerAvailable(value)} $unit"

private fun formatAvailable(value: Double?, digits: Int): String =
    if (value == null || !value.isFinite()) "不可用" else String.format(Locale.US, "%." + digits + "f", value)

private fun formatAvailableInt(value: Int?): String = value?.toString() ?: "不可用"

/** 单个工况的汇总卡片：平均功率、能量、掉电、温升、thermal 最高。 */
@Composable
private fun SummaryCard(summary: ConditionSummary, canRerun: Boolean, onRerun: () -> Unit) {
    SectionCard(
        title = "${summary.block} · ${summary.condition.label}",
        accent = if (summary.sampleCount >= SamplingConfig.SAMPLES_PER_CONDITION) LabColors.TextPrimary else LabColors.Tertiary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = "${summary.sampleCount}/${SamplingConfig.SAMPLES_PER_CONDITION} 点",
                tone = if (summary.sampleCount >= SamplingConfig.SAMPLES_PER_CONDITION) PillTone.Success else PillTone.Warning,
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(text = "thermal ${summary.thermalMax ?: "不可用"}", tone = thermalTone(summary.thermalMax))
            Spacer(Modifier.weight(1f))
            Text(
                text = "时长 ${formatAvailable(summary.durationMin, 1)} min",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = if (summary.powerMethod == "battery_level_estimate") "平均功率（估算）" else "平均功率",
                value = formatPowerAvailable(summary.avgPowerW),
                unit = "W",
                modifier = Modifier.weight(1f),
                accent = LabColors.Primary,
            )
            MetricTile(
                label = if (summary.powerMethod == "battery_level_estimate") "能量（估算）" else "能量",
                value = formatPowerAvailable(summary.energyWh),
                unit = "Wh",
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                label = "掉电",
                value = formatAvailableInt(summary.levelDropPct),
                unit = "%",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "温升",
                value = formatAvailable(summary.tempRiseC, 1),
                unit = "°C",
                modifier = Modifier.weight(1f),
                accent = if ((summary.tempRiseC ?: 0.0) >= 8.0) LabColors.Error else LabColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("起点/终点电量", "${formatAvailableInt(summary.startLevelPct)}% → ${formatAvailableInt(summary.endLevelPct)}%")
        KeyValueRow("起点/终点温度", "${formatAvailable(summary.tempStartC, 1)} → ${formatAvailable(summary.tempEndC, 1)} °C")
        KeyValueRow("功率来源", powerMethodLabel(summary.powerMethod))
        KeyValueRow(
            "功率首末时间窗",
            summary.powerWindowMin?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { "${formatAvailable(it, 2)} min（实际首末采样跨度）" } ?: "不可用",
        )
        if (summary.powerMethod == "battery_level_estimate") {
            KeyValueRow("峰值功率", "不可用（电量%仅估算窗口平均值）")
            HintText("电量% × 设定容量与电压估算，不是逐点实测；1% 电量量化误差的相对影响取决于掉电幅度，掉电越少越不稳定。")
        } else {
            KeyValueRow("最大采样/窗口功率", formatPowerWithUnit(summary.peakPowerW, "W"))
            HintText("电荷计/能量计差分得到的是窗口平均功率，其最大值不是瞬时峰值。")
        }
        KeyValueRow("平均电流", formatPowerWithUnit(summary.avgCurrentMa, "mA"))
        KeyValueRow("thermal 上升事件", formatAvailableInt(summary.thermalRiseEvents))
        KeyValueRow("平均 CPU 频率 / 亮度", "${formatAvailableInt(summary.avgCpuFreqMhz)} MHz / ${formatAvailableInt(summary.avgScreenBrightnessPct)}%")
        KeyValueRow("网络 rx / tx", "${formatAvailable(summary.netRxMb, 1)} / ${formatAvailable(summary.netTxMb, 1)} MB")
        if (summary.notes.isNotBlank()) HintText("备注：${summary.notes}")
        if (canRerun) {
            Spacer(Modifier.height(4.dp))
            LinkButton(text = "重新运行该工况（补测）", onClick = onRerun)
        }
    }
}

/** 24 行合并表预览：横屏滑动查看关键列。 */
@Composable
private fun PreviewTable(state: CampaignState, samples: Map<ConditionId, List<SessionSample>>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            listOf("block", "condition", "#", "elapsed", "level%", "current_mA", "power_W", "temp_C", "thermal", "note").forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    modifier = Modifier.width(84.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        var rowCount = 0
        state.conditions.forEach { progress ->
            val rows = samples[progress.condition].orEmpty().sortedBy { it.sampleIndex }
            rows.forEach { sample ->
                rowCount++
                Row(Modifier.fillMaxWidth()) {
                    Text(progress.block.toString(), style = MaterialTheme.typography.labelSmall, color = LabColors.Primary, modifier = Modifier.width(84.dp))
                    Text(progress.condition.id, style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text("${sample.sampleIndex}", style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text("${sample.elapsedSec}", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.width(84.dp))
                    Text(formatAvailableInt(sample.batteryLevelPct), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(
                        text = formatPowerAvailable(sample.currentNowUa?.let { it / 1000.0 }),
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextPrimary,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(formatPowerAvailable(sample.powerW), style = MaterialTheme.typography.labelSmall, color = LabColors.Primary, modifier = Modifier.width(84.dp))
                    Text(formatAvailable(sample.batteryTempC, 1), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(formatAvailableInt(sample.thermalStatus), style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary, modifier = Modifier.width(84.dp))
                    Text(sample.note, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.width(220.dp))
                }
            }
        }
        if (rowCount == 0) {
            Text("暂无采样行", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 $rowCount 行（完整 4 工况 = 24 行）",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
            )
        }
    }
}

private fun mimeOf(file: File): String = when {
    file.name.endsWith(".csv") -> "text/csv"
    file.name.endsWith(".json") -> "application/json"
    file.name.endsWith(".zip") -> "application/zip"
    file.name.endsWith(".log") -> "text/plain"
    else -> "*/*"
}
