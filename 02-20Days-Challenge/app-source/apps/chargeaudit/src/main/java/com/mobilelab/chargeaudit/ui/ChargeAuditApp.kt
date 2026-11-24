package com.mobilelab.chargeaudit.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mobilelab.chargeaudit.data.AuditState
import com.mobilelab.chargeaudit.data.BatteryReader
import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.EndReason
import com.mobilelab.chargeaudit.model.DepletionEvidence
import com.mobilelab.chargeaudit.model.Nameplate
import com.mobilelab.chargeaudit.model.NameplatePresets
import com.mobilelab.chargeaudit.R
import com.mobilelab.chargeaudit.model.Screen
import com.mobilelab.chargeaudit.service.Reminder
import com.mobilelab.core.files.FileExporter
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.time.TimeFmt
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
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun ChargeAuditApp(state: AuditState) {
    BackHandler(enabled = state.screen != Screen.HOME) { state.screen = Screen.HOME }
    when (state.screen) {
        Screen.HOME -> HomeScreen(state)
        Screen.RECORD -> RecordScreen(state)
        Screen.RESULTS -> ResultsScreen(state)
        Screen.SETTINGS -> SettingsScreen(state)
    }
}

@Composable
private fun ScreenScroll(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun MessageBanner(text: String?, onDismiss: () -> Unit) {
    if (text.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.Primary,
            modifier = Modifier.weight(1f),
        )
        LinkButton("知道了", onDismiss)
    }
}

@Composable
private fun HomeScreen(state: AuditState) {
    val context = LocalContext.current
    val summary = state.summary()
    LabScaffold(
        title = "充电核验仪",
        subtitle = "课题07 · 充电宝 USB 实际输出与铭牌核验",
        status = if (state.active) "采集中 · " + state.points.size + " 点" else "10 min / 点",
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            NextStepCard(state)
            NameplateCard(state, onEdit = { state.screen = Screen.SETTINGS })
            if (summary != null) {
                SectionCard(title = "当前会话概览") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(if (summary.outputComplete) "USB 输出能量" else "USB 已覆盖能量", ChargeMath.fmt(summary.eOutWh, 2), "Wh", modifier = Modifier.weight(1f))
                        MetricTile("手机电池净入", ChargeMath.fmt(summary.ePhoneWh, 2), "Wh", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("手机净入 / 铭牌", ChargeMath.fmt(summary.etaTotal?.times(100), 1), "%", modifier = Modifier.weight(1f))
                        MetricTile("输出/标称", ChargeMath.fmt(summary.etaOutput?.times(100), 1), "%", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("查看完整结果", { state.screen = Screen.RESULTS }, Modifier.fillMaxWidth())
                }
            }
            SectionCard(title = "实验流程") {
                HintText("1. 充电宝充满，手机固定到约 20% 电量；手机熄屏、关 Wi-Fi/蓝牙/移动数据。")
                HintText("2. USB 检测仪接在充电宝与手机之间；每 10 min 记录电压、电流与手机电量。")
                HintText("3. 手机满电或拔线不等于充电宝耗尽。若手机满电低负载导致停机，按未耗尽结束；完整实验应保持手机有足够负载。")
                Spacer(Modifier.height(6.dp))
                HintText("首点在接通负载时立即记录；仪表有累计 Wh 时，首、末点均记录，途中不要清零。")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("设置", { state.screen = Screen.SETTINGS }, Modifier.weight(1f))
                SecondaryButton("结果与导出", { state.screen = Screen.RESULTS }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NextStepCard(state: AuditState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active, state.nextDueMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    SectionCard(title = "下一步", accent = LabColors.Primary) {
        if (!state.active) {
            StatusPill("步骤 1 / 3 · 开始会话", PillTone.Info)
            Spacer(Modifier.height(8.dp))
            Text("准备开始一次完整放电", style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
            HintText("先充满充电宝、手机固定 20% 电量，再把 USB 检测仪串在中间。")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("确认充电宝满电并开始", { state.startSession() }, Modifier.fillMaxWidth())
        } else {
            StatusPill("步骤 2 / 3 · 记录中", PillTone.Success)
            Spacer(Modifier.height(8.dp))
            val remainingMs = state.nextDueMs - now
            val countdown = if (remainingMs > 0) {
                val minutes = remainingMs / 60000
                val seconds = (remainingMs % 60000) / 1000
                "下次记录还有 " + minutes + " 分 " + seconds + " 秒"
            } else {
                "已到记录时间，录入后自动续 10 分钟"
            }
            Text(
                text = "第 " + (state.points.size + 1) + " 个采样点",
                style = MaterialTheme.typography.titleMedium,
                color = LabColors.TextPrimary,
            )
            HintText(countdown + " · 已记录 " + state.points.size + " 点")
            Spacer(Modifier.height(10.dp))
            PrimaryButton("继续记录本点", { state.screen = Screen.RECORD }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("记录末点 / 结束", { state.screen = Screen.RECORD }, Modifier.weight(1f))
                SecondaryButton("结果与导出", { state.screen = Screen.RESULTS }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NameplateCard(state: AuditState, onEdit: () -> Unit) {
    val hasSession = state.sessionId != null
    val plate = if (hasSession) state.sessionNameplate ?: Nameplate() else state.nameplate
    SectionCard(title = if (hasSession) "本会话铭牌" else "充电宝铭牌") {
        KeyValueRow("型号", plate.model.ifBlank { "未填写" })
        KeyValueRow("电芯额定能量", ChargeMath.fmt(plate.ratedEnergyWh, 2) + " Wh")
        if (plate.cellTypicalMah != null || plate.cellRatedMah != null) {
            KeyValueRow("电芯容量", ChargeMath.fmt(plate.cellTypicalMah, 0) + " mAh 典型 / " +
                ChargeMath.fmt(plate.cellRatedMah, 0) + " mAh 额定")
        }
        val outputCondition = listOfNotNull(
            plate.ratedOutputV?.let { ChargeMath.fmt(it, 1, " V") },
            plate.ratedOutputCurrentA?.let { ChargeMath.fmt(it, 0, " A") },
        ).joinToString(" / ")
        KeyValueRow("额定输出容量", ChargeMath.fmt(plate.ratedOutputMah, 0, " mAh") +
            if (outputCondition.isBlank()) "" else "（$outputCondition）")
        plate.ratedInput?.let { KeyValueRow("额定输入", it) }
        plate.ratedOutput?.let { KeyValueRow("额定输出", it) }
        KeyValueRow("额定 USB 输出 / 铭牌", ChargeMath.fmt(plate.ratedOutputToLabelRatio?.times(100), 1, "%"))
        if (NameplatePresets.matchesHuaweiCp11qc(plate)) Cp11qcNameplateImage()
        if (!hasSession && !state.nameplateConfirmed) HintText("预填参数尚未确认：新实验前请核对实物并保存。")
        if (hasSession) HintText("下面的编辑只用于下次新会话，不改变本次铭牌或结果。")
        Spacer(Modifier.height(6.dp))
        LinkButton("核对／编辑铭牌参数", onEdit)
    }
}

@Composable
private fun Cp11qcNameplateImage() {
    Spacer(Modifier.height(6.dp))
    Image(
        painter = painterResource(R.drawable.cp11qc_nameplate),
        contentDescription = "华为 CP11QC 实物铭牌（已裁去二维码与序列号）",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecordScreen(state: AuditState) {
    val context = LocalContext.current
    var voltageText by remember { mutableStateOf("") }
    var currentText by remember { mutableStateOf("") }
    var meterMissing by remember { mutableStateOf(false) }
    var testerText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(BatteryReader.read(context)) }
    var showDeleteLast by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active, state.nextDueMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = state.nextDueMs - now
    LabScaffold(
        title = "记录本点",
        subtitle = state.sessionId ?: "未开始会话",
        status = if (state.active) "第 " + (state.points.size + 1) + " 点" else "已结束",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            SectionCard(title = "进度") {
                KeyValueRow("已记录", state.points.size.toString() + " 点")
                KeyValueRow("时长", ChargeMath.fmt((now - state.startMs) / 3600000.0, 2) + " h")
                KeyValueRow(
                    "下次提醒",
                    if (remaining > 0) {
                        (remaining / 60000).toString() + " 分 " + ((remaining % 60000) / 1000) + " 秒后"
                    } else {
                        "已到点"
                    },
                )
                HintText("每 10 分钟记录一次；提前/迟到记录均可，实际间隔会写进 CSV。")
            }
            SectionCard(title = "手机当前状态（自动读取）") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("电量", battery.pct?.let { it.toString() } ?: "--", "%", modifier = Modifier.weight(1f))
                    MetricTile("电池温度", ChargeMath.fmt(battery.tempC, 1), "°C", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("电压", battery.voltageMv?.toString() ?: "--", "mV", modifier = Modifier.weight(1f))
                    MetricTile("电荷计", battery.chargeUah?.toString() ?: "--", "µAh", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(when (battery.charging) { true -> "正在充电"; false -> "未充电/已满"; null -> "状态未知" },
                        if (battery.charging == true) PillTone.Success else PillTone.Warning)
                    Spacer(Modifier.width(8.dp))
                    LinkButton("刷新", { battery = BatteryReader.read(context) })
                }
                HintText("手机未充电、满电或断线不能证明充电宝耗尽。")
            }
            SectionCard(title = "录入检测仪读数", accent = LabColors.Primary) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("USB 电压 V", voltageText, { voltageText = it }, Modifier.weight(1f))
                    NumberField("USB 电流 A", currentText, { currentText = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = meterMissing, onCheckedChange = { meterMissing = it })
                    Text("检测仪熄屏或部分读数不可用")
                }
                HintText("缺读项留空，不要代填 0；仪表确实显示 0 V / 0 A 时可如实填写。")
                NumberField(
                    "仪表累计能量 Wh（可选）",
                    testerText,
                    { testerText = it },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "保存第 " + (state.points.size + 1) + " 点",
                    onClick = {
                        val voltage = parseDecimal(voltageText)
                        val current = parseDecimal(currentText)
                        val testerWh = parseDecimal(testerText)
                        if ((voltageText.isNotBlank() && voltage == null) || (currentText.isNotBlank() && current == null) ||
                            (testerText.isNotBlank() && testerWh == null)) {
                            state.message = "请填写有效数字；未知读数不要猜填"
                            return@PrimaryButton
                        }
                        if (!meterMissing && (voltage == null || current == null)) {
                            state.message = "填写检测仪电压/电流，或勾选读数不可用"
                            return@PrimaryButton
                        }
                        val latestBattery = BatteryReader.read(context)
                        battery = latestBattery
                        val error = state.addPoint(
                            usbV = voltage,
                            usbA = current,
                            testerWh = testerWh,
                            note = noteText.trim(),
                            battery = latestBattery,
                        )
                        if (error != null) state.message = error else {
                            voltageText = ""
                            currentText = ""
                            testerText = ""
                            noteText = ""
                            meterMissing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.active,
                )
                HintText("保存后自动安排下一次 10 min 提醒；如果系统限制后台，请保持通知权限开启。")
            }
            SectionCard(title = "最近记录") {
                val recent = state.points.takeLast(5).reversed()
                if (recent.isEmpty()) {
                    EmptyState("还没有采样点", "填好电压/电流后保存第 1 点")
                } else {
                    recent.forEach { point -> PointRow(point) }
                    Spacer(Modifier.height(6.dp))
                    LinkButton("删除最后一个点", { showDeleteLast = true })
                }
            }
            SecondaryButton("结束会话／补记原因", { showFinish = true }, Modifier.fillMaxWidth(), enabled = state.canRecordEndReason)
        }
    }

    if (showDeleteLast) {
        AlertDialog(
            onDismissRequest = { showDeleteLast = false },
            title = { Text("删除最后一个采样点？") },
            text = { Text("会同步重写 CSV；删除后不影响已有会话，其他点保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteLast = false
                    state.deleteLastPoint()
                }) { Text("删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteLast = false }) { Text("取消") } },
        )
    }
    if (showFinish) FinishSessionDialog(state, onDismiss = { showFinish = false })
}

@Composable
private fun FinishSessionDialog(state: AuditState, onDismiss: () -> Unit) {
    var finishReason by remember { mutableStateOf<EndReason?>(null) }
    var confirmWithoutStop by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val reportedOnly = ChargeMath.depletionEvidence(state.points, finishReason) == DepletionEvidence.REPORTED_ONLY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存会话结束原因") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HintText("选中原因后点「保存结束原因」。手机充满、拔线或低负载停机不等同于充电宝耗尽。")
                EndReason.entries.forEach { reason ->
                    TextButton(onClick = {
                        finishReason = reason
                        confirmWithoutStop = false
                        errorMessage = null
                    }) {
                        Text(if (reason == finishReason) "✓ " + reason.label else reason.label)
                    }
                }
                if (reportedOnly) {
                    HintText("没有停机末点。若有真实读数，请先返回录入；若确实亲眼确认因耗尽停机但未记录末点，可仅补记原因。不会补造读数，整程效率仍留空。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmWithoutStop, onCheckedChange = { confirmWithoutStop = it })
                        Text("我确认是耗尽停机，仅补记原因")
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val reason = finishReason ?: return@TextButton
                errorMessage = state.finishSession(reason, confirmWithoutStop)
                if (errorMessage == null) onDismiss()
            }, enabled = finishReason != null && (!reportedOnly || confirmWithoutStop)) { Text("保存结束原因") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

private fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun PointRow(point: AuditPoint) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#" + (point.index + 1), style = MaterialTheme.typography.labelMedium, color = LabColors.Primary, modifier = Modifier.width(32.dp))
        Text(
            ChargeMath.fmt(point.usbVoltageV, 2) + " V · " + ChargeMath.fmt(point.usbCurrentA, 2) + " A · " + ChargeMath.fmt(point.powerW, 2) + " W" + (point.testerWhCum?.let { " · " + ChargeMath.fmt(it, 3, " Wh 累计") } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            point.phonePct?.let { it.toString() + "%" } ?: "--",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.TextSecondary,
        )
    }
}

@Composable
private fun ResultsScreen(state: AuditState) {
    val exporter = rememberFileExporter()
    var showFinish by remember { mutableStateOf(false) }
    val summary = state.summary()
    LabScaffold(
        title = "结果与导出",
        subtitle = state.sessionId ?: "未开始会话",
        status = (if (state.endReason != null) "已保存结束原因 · " else "") + state.points.size.toString() + " 点",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            SectionCard(title = "会话结束状态") {
                KeyValueRow("结束原因", state.endReason?.label ?: if (state.active) "采集中，未保存结束原因" else "未记录")
                if (state.depletionEvidence == DepletionEvidence.REPORTED_ONLY) {
                    HintText("用户确认耗尽，但未录到停机末点。原始采样不变，整程效率留空。")
                }
                if (state.canRecordEndReason) {
                    SecondaryButton("结束会话／补记原因", { showFinish = true }, Modifier.fillMaxWidth())
                }
            }
            if (summary == null) {
                SectionCard(title = "数据不足") {
                    EmptyState("至少需要 2 个采样点", "继续记录后再查看效率")
                }
            } else {
                SectionCard(title = "能量汇总", accent = LabColors.Primary) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(if (summary.outputComplete) "USB 输出能量" else "USB 已覆盖能量", ChargeMath.fmt(summary.eOutWh, 3), "Wh", modifier = Modifier.weight(1f))
                        MetricTile("手机电池净入", ChargeMath.fmt(summary.ePhoneWh, 3), "Wh", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("输出/标称", ChargeMath.fmt(summary.etaOutput?.times(100), 1), "%", modifier = Modifier.weight(1f))
                        MetricTile("手机净入 / 铭牌", ChargeMath.fmt(summary.etaTotal?.times(100), 1), "%", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("时长", ChargeMath.fmt(summary.durationH, 2) + " h")
                    KeyValueRow("平均/峰值功率", ChargeMath.fmt(summary.avgPowerW, 2) + " W / " + ChargeMath.fmt(summary.peakPowerW, 2) + " W")
                    KeyValueRow("已覆盖 / 采样时长", ChargeMath.fmt(summary.outputCoveredH, 2, " h") + " / " + ChargeMath.fmt(summary.durationH, 2, " h"))
                    KeyValueRow("USB 能量方法", when (summary.outputMethod) {
                        "tester_cumulative_delta" -> "仪表累计 Wh 末值 − 首值"
                        "trapezoid" -> "实测功率梯形积分估算"
                        else -> "不可用"
                    })
                    KeyValueRow("手机电池净入能量方法", when (summary.phoneEnergyMethod) {
                        "charge_counter" -> "（电荷计末值 − 首值）× 平均电池电压"
                        "charge_counter_x_nominal_voltage" -> "电荷计差 × 标称电池电压"
                        "battery_pct_x_capacity" -> "电量变化 × 手机容量 × 标称电压"
                        else -> "不可用"
                    })
                    KeyValueRow("等效输出容量", ChargeMath.fmt(summary.equivOutputMah, 0, " mAh") + " @ " + ChargeMath.fmt(state.sessionNameplate?.ratedOutputV, 1, " V"))
                    KeyValueRow("手机电量", (summary.startPct?.let { "$it%" } ?: "--") + " → " + (summary.endPct?.let { "$it%" } ?: "--"))
                    if (summary.counterDecreased) HintText("累计 Wh 曾回退（可能清零或换表），未使用累计差。")
                    if (summary.outputMethod == "trapezoid") HintText("两个采样点之间按直线估算；骤降、漏采和终点记录延迟会影响结果。")
                    if (summary.outOfRangeCurrentPoints > 0) HintText("有 " + summary.outOfRangeCurrentPoints + " 个电流读数超过铭牌最大值；原始值保留。功率积分不跨这些点。")
                    if (!summary.outputComplete) HintText("有未覆盖区间：不补零、不跨无效点积分，不计算整程输出/铭牌。")
                    if (!summary.fullDischarge) HintText("尚未确认满电到耗尽；输出仅代表记录区间，不计算整程输出/铭牌。")
                }
                SampleCurves(state.points)
                SectionCard(title = "差额与损耗说明") {
                    KeyValueRow("铭牌 − USB 输出", ChargeMath.fmt(summary.labelMinusOutputWh, 3, " Wh"))
                    KeyValueRow("USB 输出 − 手机电池净入", ChargeMath.fmt(summary.outputMinusPhoneWh, 3, " Wh"))
                    KeyValueRow("铭牌 − 手机电池净入", ChargeMath.fmt(summary.labelMinusPhoneWh, 3, " Wh"))
                    HintText("输出/铭牌是整机在该负载下的结果，不是升压芯片效率。铭牌与实测的差额包含实际电芯容量偏差、截止后剩余能量、保护/转换损耗及测量误差。")
                    HintText("USB 输出与手机电池净入的差额还含线材、充电电路、手机运行耗电和估算误差；不能据此拆出各项，负差额不强行记为零。")
                }
            }
            if (state.sessionId != null) {
                SectionCard(title = "导出") {
                    HintText("ZIP 含原始 CSV、铭牌和结束原因、能量汇总。无法计算的 JSON 指标保留为 null。")
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("生成并分享 ZIP", {
                        val zip = state.exportZip()
                        if (zip == null) {
                            if (state.message == null) state.message = "没有可导出的会话数据"
                        } else {
                            exporter.share(zip, "application/zip")
                            state.message = "已生成 " + zip.name
                        }
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("保存 ZIP", {
                            val zip = state.exportZip()
                            if (zip != null) exporter.saveAs(zip, "application/zip") else if (state.message == null) state.message = "没有可导出的数据"
                        }, Modifier.weight(1f))
                        SecondaryButton("分享 CSV", {
                            val csv = state.csvFile()
                            if (csv != null) exporter.share(csv, "text/csv") else if (state.message == null) state.message = "没有 CSV"
                        }, Modifier.weight(1f))
                    }
                }
            }
            SectionCard(title = "原始表预览") {
                if (state.points.isEmpty()) {
                    EmptyState("暂无数据", "先记录采样点")
                } else {
                    state.points.takeLast(12).forEach { point -> PointRow(point) }
                    HintText("完整 CSV 保留原始读数；追加仪表累计 Wh 列，缺读为空。")
                }
            }
        }
    }
    if (showFinish) FinishSessionDialog(state, onDismiss = { showFinish = false })
}

@Composable
private fun SampleCurves(points: List<AuditPoint>) {
    val ordered = points.sortedBy { it.elapsedMin }
    val span = ordered.last().elapsedMin - ordered.first().elapsedMin
    if (span <= 0.0 || !span.isFinite()) return
    SectionCard(title = "采样曲线") {
        HintText("横轴为首点后经过时间；缺读断开，不用零代替。")
        SampleTrace("USB 电压", "V", ordered, span) { it.usbVoltageV }
        SampleTrace("USB 电流", "A", ordered, span) { it.usbCurrentA }
        SampleTrace("USB 功率", "W", ordered, span) { it.powerW }
        SampleTrace("手机电量", "%", ordered, span, 100.0) { it.phonePct?.toDouble() }
        KeyValueRow("时间范围", "0 – " + ChargeMath.fmt(span, 1, " min"))
    }
}

@Composable
private fun SampleTrace(
    label: String, unit: String, points: List<AuditPoint>, span: Double, limit: Double? = null,
    read: (AuditPoint) -> Double?,
) {
    val values = points.map { read(it)?.takeIf { v -> v.isFinite() && v >= 0 } }
    val max = limit ?: values.filterNotNull().maxOrNull()
    if (max == null || values.all { it == null }) {
        KeyValueRow(label, "无读数")
        return
    }
    KeyValueRow(label, "0 – " + ChargeMath.fmt(max, 2, " $unit"))
    Canvas(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp, vertical = 3.dp)) {
        drawLine(LabColors.TextSecondary, Offset(0f, size.height), Offset(size.width, size.height))
        var previous: Offset? = null
        points.zip(values).forEach { (point, value) ->
            val next = value?.let {
                val x = ((point.elapsedMin - points.first().elapsedMin) / span).toFloat()
                val y = if (max > 0) (it / max).coerceIn(0.0, 1.0).toFloat() else 0f
                Offset(x * size.width, (1f - y) * size.height)
            }
            if (next != null) {
                previous?.let { drawLine(LabColors.Primary, it, next, strokeWidth = 2.dp.toPx()) }
                drawCircle(LabColors.Primary, radius = 2.dp.toPx(), center = next)
            }
            previous = next
        }
    }
}

@Composable
private fun SettingsScreen(state: AuditState) {
    val context = LocalContext.current
    var bankDetails by remember { mutableStateOf(state.nameplate) }
    var model by remember { mutableStateOf(state.nameplate.model) }
    var ratedWh by remember { mutableStateOf(state.nameplate.ratedEnergyWh?.toString().orEmpty()) }
    var ratedMah by remember { mutableStateOf(state.nameplate.ratedOutputMah?.toString().orEmpty()) }
    var ratedV by remember { mutableStateOf(state.nameplate.ratedOutputV?.toString().orEmpty()) }
    var phoneMah by remember { mutableStateOf(state.nameplate.phoneCapacityMah?.toString().orEmpty()) }
    var phoneV by remember { mutableStateOf(state.nameplate.phoneNominalV?.toString().orEmpty()) }
    var tester by remember { mutableStateOf(state.nameplate.testerModel) }
    var showDelete by remember { mutableStateOf(false) }
    LabScaffold(
        title = "设置",
        subtitle = "铭牌参数、提醒与数据管理",
        onBack = { state.screen = Screen.HOME },
    ) {
        ScreenScroll {
            MessageBanner(state.message) { state.message = null }
            SectionCard(title = "华为 CP11QC · 实物铭牌预设") {
                Cp11qcNameplateImage()
                HintText("34.49 Wh · 额定输出 6200 mAh（5 V / 2 A）。使用预设只填写充电宝参数，不改手机或仪表参数。")
                Spacer(Modifier.height(8.dp))
                SecondaryButton("使用华为 CP11QC 铭牌", {
                    val preset = NameplatePresets.huaweiCp11qc
                    bankDetails = preset
                    model = preset.model
                    ratedWh = preset.ratedEnergyWh?.toString().orEmpty()
                    ratedMah = preset.ratedOutputMah?.toString().orEmpty()
                    ratedV = preset.ratedOutputV?.toString().orEmpty()
                    state.message = "已填入 CP11QC 预设，请核对后点下方「确认并保存实物参数」"
                }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "充电宝 / 手机参数") {
                OutlinedTextField(model, { model = it }, label = { Text("充电宝型号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("额定能量 Wh", ratedWh, { ratedWh = it }, Modifier.weight(1f))
                    NumberField("额定输出 mAh", ratedMah, { ratedMah = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("额定输出 V", ratedV, { ratedV = it }, Modifier.weight(1f))
                    NumberField("手机电池 mAh", phoneMah, { phoneMah = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("手机标称 V", phoneV, { phoneV = it }, Modifier.weight(1f))
                    OutlinedTextField(tester, { tester = it }, label = { Text("测试仪型号") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(10.dp))
                HintText("请按实物核对铭牌 Wh。其余为选填；未知项留空。新参数用于下一次新会话。")
                PrimaryButton("确认并保存实物参数", {
                    val fields = listOf(ratedWh, ratedMah, ratedV, phoneMah, phoneV)
                    val values = fields.map(::parseDecimal)
                    if (ratedWh.isBlank() || fields.zip(values).any { (text, value) ->
                            text.isNotBlank() && (value == null || !value.isFinite() || value <= 0)
                        }) {
                        state.message = "铭牌 Wh 必填；所有已填数值必须为有限的正数"
                        return@PrimaryButton
                    }
                    val bank = NameplatePresets.updateBank(bankDetails, model.trim(), values[0], values[1], values[2])
                    state.saveNameplate(bank.copy(
                        phoneCapacityMah = values[3],
                        phoneNominalV = values[4],
                        testerModel = tester.trim(),
                    ))
                }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "提醒与权限") {
                HintText("每次保存后，App 会安排 10 分钟后的提醒；ColorOS 可能延迟后台闹钟，建议允许通知/精确闹钟。")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("通知设置", { openNotificationSettings(context) }, Modifier.weight(1f))
                    SecondaryButton("精确闹钟", { openExactAlarmSettings(context) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("发送一条测试提醒", { Reminder.postNow(context) }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "数据管理", accent = LabColors.Error) {
                HintText("删除当前会话会移除 CSV/manifest 并清空状态；不可恢复，建议先导出 ZIP。")
                Spacer(Modifier.height(8.dp))
                SecondaryButton("删除本会话数据", { showDelete = true }, Modifier.fillMaxWidth())
            }
            SectionCard(title = "关于") {
                HintText("充电核验仪 · 课题07：充电宝标称容量与转换效率核验")
                HintText("数据目录：Android/data/com.mobilelab.chargeaudit/files/experiments/chargeaudit/")
                HintText("配套分析脚本：analysis/charge/analyze.py（仓库）")
                HintText("版本 " + com.mobilelab.chargeaudit.BuildConfig.VERSION_NAME)
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除本会话数据？") },
            text = { Text("将删除 CSV、manifest 与当前进度，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    state.resetSession(true)
                }) { Text("删除", color = LabColors.Error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(("package:" + context.packageName).toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
