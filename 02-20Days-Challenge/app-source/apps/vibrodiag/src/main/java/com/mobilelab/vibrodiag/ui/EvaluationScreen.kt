package com.mobilelab.vibrodiag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobilelab.core.files.FileExporter
import com.mobilelab.core.files.Storage
import com.mobilelab.core.files.rememberFileExporter
import com.mobilelab.core.ui.BarSeries
import com.mobilelab.core.ui.HintText
import com.mobilelab.core.ui.KeyValueRow
import com.mobilelab.core.ui.LabScaffold
import com.mobilelab.core.ui.LabTextField
import com.mobilelab.core.ui.LineChart
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.MetricTile
import com.mobilelab.core.ui.PillTone
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.StatusPill
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.vibrodiag.AppState
import com.mobilelab.vibrodiag.Screen
import com.mobilelab.vibrodiag.data.EvaluationReport
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.model.Conditions
import java.util.Locale

@Composable
fun EvaluationScreen(state: AppState) {
    val exporter = rememberFileExporter()
    var notes by rememberSaveable { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }

    LaunchedEffect(state.exportFile) {
        if (state.exportFile != null) showExportDialog = true
    }

    LabScaffold(
        title = "评估与报告",
        subtitle = "留一法 · 混淆矩阵 · 白/粉红噪声抗噪",
        status = "训练集 " + state.usableSamples().size + " 组",
        onBack = { state.screen = Screen.Home },
    ) {
        ScrollColumn {
            SectionCard(title = "评估口径") {
                HintText("本页的留一法和抗噪为 App 自带 kNN 评估；正式盲测使用内置 MATLAB 核岭模型。报告按模型分别标注，请勿混算。")
            }
            DatasetStatsCard(state)
            TrainingCard(state)
            NoiseCard(state)
            ReportCard(
                state = state,
                notes = notes,
                onNotesChange = { notes = it },
                onShowJson = {
                    jsonText = state.evaluationReportText()
                    showJsonDialog = true
                },
                onGenerated = { showReportDialog = true },
            )
        }
    }

    if (showExportDialog) {
        val file = state.exportFile
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                state.clearExportFile()
            },
            containerColor = LabColors.Surface,
            title = { Text("导出会话 ZIP", color = LabColors.TextPrimary) },
            text = {
                Column {
                    HintText("ZIP 包含全部录音、特征 CSV、盲测/抗噪 CSV、评估 JSON、manifest 与 session.log。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (file == null) "正在生成…" else Storage.humanBytes(file.length()),
                        style = MaterialTheme.typography.labelMedium,
                        color = LabColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = file != null,
                    onClick = { file?.let { exporter.share(it, "application/zip") } },
                ) { Text("分享", color = LabColors.Primary) }
            },
            dismissButton = {
                Column {
                    LinkButton("保存到文件…", { file?.let { exporter.saveAs(it, "application/zip") } })
                    TextButton(onClick = {
                        showExportDialog = false
                        state.clearExportFile()
                    }) { Text("关闭", color = LabColors.TextSecondary) }
                }
            },
        )
    }

    if (showReportDialog) {
        val file = state.evaluationFile
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = LabColors.Surface,
            title = { Text("评估报告已生成", color = LabColors.Success) },
            text = {
                Column {
                    Text(
                        text = file?.name ?: "evaluation JSON",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    HintText("包含 training(LOOCV/混淆矩阵/逐工况准确率)、blind_test、noise、完整 model 参数与 conditions。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = file?.absolutePath ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkButton("查看 JSON 内容", {
                        jsonText = state.evaluationReportText()
                        showReportDialog = false
                        showJsonDialog = true
                    })
                    LinkButton("保存 JSON…", { file?.let { exporter.saveAs(it, "application/json") } })
                }
            },
            confirmButton = {
                TextButton(onClick = { file?.let { exporter.share(it, "application/json") } }) {
                    Text("分享", color = LabColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("关闭", color = LabColors.TextSecondary) }
            },
        )
    }

    if (showJsonDialog) {
        TextContentDialog("evaluation JSON", jsonText) { showJsonDialog = false }
    }
}

@Composable
private fun DatasetStatsCard(state: AppState) {
    val counts = state.countsByCondition()
    SectionCard(title = "数据集统计") {
        for (condition in Conditions.all) {
            KeyValueRow(condition.label, (counts[condition.id] ?: 0).toString() + " / " + Conditions.TARGET_GROUPS)
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("训练集样本", state.usableSamples().size.toString())
        KeyValueRow("特征维度", com.mobilelab.vibrodiag.features.FeatureNames.count.toString())
        val interrupted = state.samples.count { it.interrupted }
        if (interrupted > 0) {
            Spacer(Modifier.height(4.dp))
            StatusPill("不完整样本 " + interrupted + " 组（不参与训练）", PillTone.Warning)
        }
    }
}

@Composable
private fun TrainingCard(state: AppState) {
    val loocv = state.loocv
    SectionCard(title = "留一法（LOOCV）评估") {
        if (loocv == null) {
            HintText("使用当前全部已标注样本做留一法交叉验证（每折重新计算 z-score 统计量）。")
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                "运行留一法评估",
                { state.runLoocv() },
                Modifier.fillMaxWidth(),
                enabled = state.usableSamples().size >= 2,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "LOOCV 准确率",
                    Fmt.value(loocv.accuracy * 100.0, 1),
                    "%",
                    modifier = Modifier.weight(1f),
                    accent = LabColors.Success,
                )
                MetricTile(
                    "正确 / 总数",
                    loocv.correct.toString() + " / " + loocv.total,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("逐工况准确率", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val perCondition = loocv.perConditionAccuracy()
            BarSeries(
                items = Conditions.all.map { it.shortLabel to ((perCondition[it.id] ?: 0.0) * 100.0).toFloat() },
                color = LabColors.Primary,
                unit = "%",
                highlightIndex = Conditions.all.indices.maxByOrNull { perCondition[Conditions.all[it].id] ?: 0.0 } ?: -1,
            )
            Spacer(Modifier.height(12.dp))
            Text("混淆矩阵（行=真实，列=预测）", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            ConfusionMatrixTable(labels = Conditions.ids, matrix = loocv.confusionMatrix())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("重新计算", { state.runLoocv() }, Modifier.fillMaxWidth())
        }
    }
}

private fun noiseLabel(type: NoiseType): String = if (type == NoiseType.WHITE) "白噪声" else "粉红噪声"

@Composable
private fun NoiseCard(state: AppState) {
    val result = state.noiseResult
    val types = NoiseEvaluator.DEFAULT_NOISE_TYPES
    val levels = NoiseEvaluator.DEFAULT_SNR_LEVELS
    var noiseMask by rememberSaveable { mutableStateOf((1 shl types.size) - 1) }
    var levelMask by rememberSaveable { mutableStateOf((1 shl levels.size) - 1) }
    SectionCard(title = "抗噪评估（白噪声 / 粉红噪声）") {
        HintText("可选一种或两种噪声和所需 SNR 档位。每次留一：只对留出的样本加噪，其他样本保持干净作为训练集。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEachIndexed { index, type ->
                val bit = 1 shl index
                FilterChip(selected = noiseMask and bit != 0, onClick = { noiseMask = noiseMask xor bit },
                    label = { Text(noiseLabel(type)) }, enabled = !state.noiseRunning)
            }
        }
        levels.indices.chunked(3).forEach { indices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                indices.forEach { index ->
                    val bit = 1 shl index
                    FilterChip(selected = levelMask and bit != 0, onClick = { levelMask = levelMask xor bit },
                        label = { Text(levels[index].toString() + " dB") }, enabled = !state.noiseRunning)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.noiseRunning) {
            Text(
                "正在提取加噪特征并分类：" + Fmt.value(state.noiseProgress * 100.0, 0) + "%",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.noiseProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = LabColors.Secondary,
                trackColor = LabColors.SurfaceHigh,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("取消", { state.cancelNoiseEvaluation() }, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(
                "运行抗噪评估",
                {
                    state.runNoiseEvaluation(
                        types.filterIndexed { index, _ -> noiseMask and (1 shl index) != 0 },
                        levels.filterIndexed { index, _ -> levelMask and (1 shl index) != 0 },
                    )
                },
                Modifier.fillMaxWidth(),
                enabled = state.usableSamples().size >= 2 && noiseMask != 0 && levelMask != 0,
            )
        }
        if (result != null) {
            Spacer(Modifier.height(12.dp))
            for (type in result.noiseTypes) {
                val values = result.snrLevels.mapNotNull { snr ->
                    result.accuracy(type, snr).takeIf { it.isFinite() }?.toFloat()
                }
                if (values.size >= 2) {
                    Text(noiseLabel(type) + "准确率曲线", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                    LineChart(values, Modifier.fillMaxWidth(), color = if (type == NoiseType.WHITE) LabColors.Primary else LabColors.Secondary,
                        height = 110.dp, minValue = 0f, maxValue = 1f)
                    Spacer(Modifier.height(8.dp))
                }
            }
            HintText("本次 SNR 从左到右：" + result.snrLevels.joinToString(" / ") { it.toString() + " dB" })
            for (snr in result.snrLevels) {
                val values = result.noiseTypes.joinToString(" · ") { type ->
                    noiseLabel(type) + " " + Fmt.percent(result.accuracy(type, snr))
                }
                KeyValueRow(snr.toString() + " dB", values)
            }
            Spacer(Modifier.height(6.dp))
            HintText("逐工况明细见 " + state.repository.noiseCsvFile.name + "（共 " + result.rows.size + " 行）")
        } else {
            Spacer(Modifier.height(8.dp))
            val rows = state.readCurrentNoiseRows()
            HintText(
                if (rows.isNotEmpty()) "已从 noise_eval CSV 读取 " + rows.size + " 行结果，可生成评估报告。"
                else if (state.repository.noiseCsvFile.isFile) "旧版抗噪 CSV 未使用留一验证，请重新运行。"
                else "尚未运行抗噪评估。"
            )
        }
    }
}

@Composable
private fun ReportCard(
    state: AppState,
    notes: String,
    onNotesChange: (String) -> Unit,
    onShowJson: () -> Unit,
    onGenerated: () -> Unit,
) {
    SectionCard(title = "评估报告") {
        LabTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = "现场备注（写入 evaluation.notes）",
            placeholder = "如：手机平放，风扇底座 30cm，安静实验室",
            singleLine = false,
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "生成评估报告 JSON",
            {
                if (state.generateEvaluationReport(notes) != null) onGenerated()
            },
            Modifier.fillMaxWidth(),
            enabled = state.usableSamples().isNotEmpty(),
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton("导出会话 ZIP（含全部数据）", { state.createSessionZipAsync() }, Modifier.fillMaxWidth())
        if (state.evaluationFile != null) {
            Spacer(Modifier.height(8.dp))
            LinkButton("查看当前 evaluation JSON", { onShowJson() })
            Text(
                text = state.evaluationFile?.name ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        HintText("评估 JSON 内含训练集统计、LOOCV、盲测、抗噪曲线与完整模型参数，可直接复现实验。")
    }
}