package com.mobilelab.chargeaudit.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.service.Reminder
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.ChargeSummary
import com.mobilelab.chargeaudit.model.EndReason
import com.mobilelab.chargeaudit.model.DepletionEvidence
import com.mobilelab.chargeaudit.model.Nameplate
import com.mobilelab.chargeaudit.model.NameplatePresets
import com.mobilelab.core.files.Storage
import com.mobilelab.core.time.TimeFmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 电池状态快照（保存采样点时自动读取）。 */
data class BatteryReading(
    val pct: Int?,
    val voltageMv: Int?,
    val tempC: Double?,
    val chargeUah: Long?,
    val currentUa: Long?,
    val charging: Boolean?,
)

object BatteryReader {
    fun read(context: Context): BatteryReading {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        fun prop(id: Int): Long? = runCatching { manager.getLongProperty(id) }
            .getOrNull()?.takeIf { it != Long.MIN_VALUE }
        val battery = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (scale > 0 && level in 0..scale) (level * 100L / scale).toInt() else null
        val voltage = battery?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val tempRaw = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (tempRaw != Int.MIN_VALUE) tempRaw / 10.0 else null
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> true
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            android.os.BatteryManager.BATTERY_STATUS_FULL -> false
            else -> null
        }
        return BatteryReading(
            pct = pct,
            voltageMv = voltage,
            tempC = temp,
            chargeUah = prop(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0 },
            currentUa = prop(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            charging = charging,
        )
    }
}

/**
 * 会话状态与持久化：prefs 里存 JSON，CSV 每采一点就追加，进程被杀后可恢复。
 */
class AuditState(private val context: Context) {

    companion object {
        const val PREFS = "chargeaudit_state"
        const val KEY_STATE = "state_json"
        const val INTERVAL_MS = 10 * 60 * 1000L
        const val CSV_HEADER = AuditExport.CSV_HEADER
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var nameplate by androidx.compose.runtime.mutableStateOf(NameplatePresets.huaweiCp11qc)
        private set
    var nameplateConfirmed by androidx.compose.runtime.mutableStateOf(false)
        private set
    var sessionNameplate by androidx.compose.runtime.mutableStateOf<Nameplate?>(null)
        private set
    private var fullStartConfirmed = false
    private var endReasonRecordedAtMs: Long? = null
    var endReason by androidx.compose.runtime.mutableStateOf<EndReason?>(null)
        private set
    var points by androidx.compose.runtime.mutableStateOf<List<AuditPoint>>(emptyList())
        private set
    var sessionId by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var sessionDir by androidx.compose.runtime.mutableStateOf<File?>(null)
        private set
    var startMs by androidx.compose.runtime.mutableStateOf(0L)
        private set
    var nextDueMs by androidx.compose.runtime.mutableStateOf(0L)
        private set
    var active by androidx.compose.runtime.mutableStateOf(false)
        private set
    var screen by androidx.compose.runtime.mutableStateOf(com.mobilelab.chargeaudit.model.Screen.HOME)
    var message by androidx.compose.runtime.mutableStateOf<String?>(null)

    init {
        load()
    }

    private fun JSONObject.number(key: String): Double? = optDouble(key).takeIf { it.isFinite() }

    private fun JSONObject.readNameplate(): Nameplate = Nameplate(
        model = optString("model"),
        ratedEnergyWh = number("ratedEnergyWh")?.takeIf { it > 0 },
        ratedOutputMah = number("ratedOutputMah")?.takeIf { it > 0 },
        ratedOutputV = number("ratedOutputV")?.takeIf { it > 0 },
        cellTypicalMah = number("cellTypicalMah")?.takeIf { it > 0 },
        cellRatedMah = number("cellRatedMah")?.takeIf { it > 0 },
        phoneCapacityMah = number("phoneCapacityMah")?.takeIf { it > 0 },
        phoneNominalV = number("phoneNominalV")?.takeIf { it > 0 },
        testerModel = optString("testerModel"),
        ratedOutputCurrentA = number("ratedOutputCurrentA")?.takeIf { it > 0 },
        ratedInput = if (isNull("ratedInput")) null else optString("ratedInput").takeIf { it.isNotBlank() },
        ratedOutput = if (isNull("ratedOutput")) null else optString("ratedOutput").takeIf { it.isNotBlank() },
    )

    private fun Nameplate.toJson(): JSONObject = JSONObject().apply {
        put("model", model)
        put("ratedEnergyWh", ratedEnergyWh ?: JSONObject.NULL)
        put("ratedOutputMah", ratedOutputMah ?: JSONObject.NULL)
        put("ratedOutputV", ratedOutputV ?: JSONObject.NULL)
        put("cellTypicalMah", cellTypicalMah ?: JSONObject.NULL)
        put("cellRatedMah", cellRatedMah ?: JSONObject.NULL)
        put("phoneCapacityMah", phoneCapacityMah ?: JSONObject.NULL)
        put("phoneNominalV", phoneNominalV ?: JSONObject.NULL)
        put("testerModel", testerModel)
        put("ratedOutputCurrentA", ratedOutputCurrentA ?: JSONObject.NULL)
        put("ratedInput", ratedInput ?: JSONObject.NULL)
        put("ratedOutput", ratedOutput ?: JSONObject.NULL)
    }

    private fun load() {
        val raw = prefs.getString(KEY_STATE, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("nameplate")?.let { nameplate = it.readNameplate() }
            nameplateConfirmed = root.optBoolean("nameplateConfirmed", false)
            fullStartConfirmed = root.optBoolean("fullStartConfirmed", false)
            sessionNameplate = root.optJSONObject("sessionNameplate")?.readNameplate()
            val savedReason = root.optString("endReason")
            endReason = EndReason.entries.firstOrNull { it.name == savedReason }
            endReasonRecordedAtMs = root.optLong("endReasonRecordedAtMs").takeIf { it > 0 }
            sessionId = root.optString("sessionId", "").takeIf { it.isNotBlank() }
            val dir = root.optString("sessionDir", "").takeIf { it.isNotBlank() }
            sessionDir = dir?.let { File(it) }
            startMs = root.optLong("startMs", 0L)
            nextDueMs = root.optLong("nextDueMs", 0L)
            active = root.optBoolean("active", false)
            val array = root.optJSONArray("points") ?: JSONArray()
            points = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        AuditPoint(
                            index = item.optInt("index", index),
                            timestampMs = item.optLong("timestampMs", 0L),
                            elapsedMin = item.optDouble("elapsedMin", 0.0),
                            usbVoltageV = item.number("usbVoltageV")?.takeIf { it >= 0 },
                            usbCurrentA = item.number("usbCurrentA")?.takeIf { it >= 0 },
                            testerWhCum = item.number("testerWhCum")?.takeIf { it >= 0 },
                            phonePct = item.optInt("phonePct", -1).takeIf { it >= 0 },
                            phoneVoltageMv = item.optInt("phoneVoltageMv", -1).takeIf { it > 0 },
                            phoneChargeUah = item.optLong("phoneChargeUah", -1L).takeIf { it >= 0 },
                            phoneTempC = item.optDouble("phoneTempC", Double.NaN).takeIf { it.isFinite() },
                            charging = item.opt("charging") as? Boolean,
                            note = item.optString("note", ""),
                        ),
                    )
                }
            }
        }.onFailure { message = "读取上次会话失败：" + it.message }
    }

    private data class RecordedEnd(val reason: EndReason, val savedAtMs: Long)

    private fun save(ending: RecordedEnd? = null): Boolean {
        val root = JSONObject()
        root.put("nameplate", nameplate.toJson())
        root.put("nameplateConfirmed", nameplateConfirmed)
        root.put("sessionNameplate", sessionNameplate?.toJson() ?: JSONObject.NULL)
        root.put("fullStartConfirmed", fullStartConfirmed)
        root.put("endReason", (ending?.reason ?: endReason)?.name ?: JSONObject.NULL)
        root.put("endReasonRecordedAtMs", ending?.savedAtMs ?: endReasonRecordedAtMs ?: JSONObject.NULL)
        root.put("sessionId", sessionId ?: "")
        root.put("sessionDir", sessionDir?.absolutePath ?: "")
        root.put("startMs", startMs)
        root.put("nextDueMs", nextDueMs)
        root.put("active", ending == null && active)
        root.put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().apply {
                    put("index", point.index)
                    put("timestampMs", point.timestampMs)
                    put("elapsedMin", point.elapsedMin)
                    put("usbVoltageV", point.usbVoltageV ?: JSONObject.NULL)
                    put("usbCurrentA", point.usbCurrentA ?: JSONObject.NULL)
                    point.testerWhCum?.let { put("testerWhCum", it) }
                    point.phonePct?.let { put("phonePct", it) }
                    point.phoneVoltageMv?.let { put("phoneVoltageMv", it) }
                    point.phoneChargeUah?.let { put("phoneChargeUah", it) }
                    point.phoneTempC?.let { put("phoneTempC", it) }
                    put("charging", point.charging ?: JSONObject.NULL)
                    put("note", point.note)
                })
            }
        })
        val serialized = root.toString()
        // End confirmation must survive a process restart before the UI reports success.
        if (ending != null) return prefs.edit().putString(KEY_STATE, serialized).commit()
        prefs.edit { putString(KEY_STATE, serialized) }
        return true
    }

    fun saveNameplate(updated: Nameplate) {
        val rated = updated.ratedEnergyWh
        if (rated == null || !rated.isFinite() || rated <= 0 ||
            listOfNotNull(updated.ratedOutputMah, updated.ratedOutputV, updated.phoneCapacityMah, updated.phoneNominalV,
                updated.cellRatedMah, updated.cellTypicalMah, updated.ratedOutputCurrentA).any { !it.isFinite() || it <= 0 }) {
            message = "请输入真实铭牌 Wh（大于 0）；未知选填项请留空"
            return
        }
        nameplate = updated
        nameplateConfirmed = true
        save()
        message = if (sessionId == null) "铭牌参数已保存" else "铭牌参数已保存，将用于下一次新会话；已有会话不变"
    }

    fun startSession() {
        if (active) return
        if (!nameplateConfirmed || nameplate.ratedEnergyWh == null) {
            message = "请先核对并保存实际充电宝的铭牌 Wh"
            screen = com.mobilelab.chargeaudit.model.Screen.SETTINGS
            return
        }
        val now = System.currentTimeMillis()
        val id = "CA-" + TimeFmt.fileStamp(now)
        val dir = runCatching {
            Storage.newSessionDir(context, "chargeaudit", id).also { writeCsv(File(it, "chargeaudit_$id.csv"), emptyList()) }
        }.getOrElse {
            message = "创建会话 CSV 失败：" + it.message
            return
        }
        sessionId = id
        sessionDir = dir
        sessionNameplate = nameplate
        fullStartConfirmed = true
        endReason = null
        endReasonRecordedAtMs = null
        startMs = now
        nextDueMs = startMs + INTERVAL_MS
        points = emptyList()
        active = true
        writeMeta()
        save()
        Reminder.schedule(context, nextDueMs)
        message = "新会话已开始：接通负载时立即记录首点，再每 10 分钟记录"
        screen = com.mobilelab.chargeaudit.model.Screen.RECORD
    }

    fun addPoint(usbV: Double?, usbA: Double?, testerWh: Double?, note: String, battery: BatteryReading): String? {
        if (!active) return "会话未开始"
        ChargeMath.validateMeterReading(usbV, usbA, testerWh)?.let { return it }
        val now = System.currentTimeMillis()
        val elapsed = (now - startMs) / 60000.0
        if (elapsed < 0 || points.lastOrNull()?.let { elapsed <= it.elapsedMin } == true) return "时间异常：采样必须晚于上一点"
        val point = AuditPoint(
            index = points.size,
            timestampMs = now,
            elapsedMin = elapsed,
            usbVoltageV = usbV,
            usbCurrentA = usbA,
            testerWhCum = testerWh,
            phonePct = battery.pct,
            phoneVoltageMv = battery.voltageMv,
            phoneChargeUah = battery.chargeUah,
            phoneTempC = battery.tempC,
            charging = battery.charging,
            note = note,
        )
        val updated = points + point
        val file = rawCsvFile() ?: return "没有可写入的会话 CSV"
        runCatching { writeCsv(file, updated) }.onFailure { return "写入 CSV 失败：" + it.message }
        points = updated
        nextDueMs = now + INTERVAL_MS
        save()
        Reminder.schedule(context, nextDueMs)
        val power = point.powerW?.let { ChargeMath.fmt(it, 2, " W") } ?: "USB 功率缺读"
        message = "第 " + (point.index + 1) + " 点已保存（" + power + "）"
        return null
    }

    fun deleteLastPoint() {
        if (!active || points.isEmpty()) return
        val updated = points.dropLast(1)
        val file = rawCsvFile() ?: return
        runCatching { writeCsv(file, updated) }.onFailure { message = "更新 CSV 失败：" + it.message; return }
        points = updated
        nextDueMs = System.currentTimeMillis() + INTERVAL_MS
        save()
        Reminder.schedule(context, nextDueMs)
        message = "已删除最后一个采样点"
    }

    val canRecordEndReason: Boolean get() = sessionId != null && (active || (endReason == null && points.isNotEmpty()))
    val depletionEvidence: DepletionEvidence? get() = ChargeMath.depletionEvidence(points, endReason)
    private fun endReasonRecordedAt(): String? = endReasonRecordedAtMs?.let(TimeFmt::isoLocal)

    fun finishSession(reason: EndReason, confirmWithoutStopSample: Boolean = false): String? {
        if (!canRecordEndReason) return "当前没有可记录结束原因的会话"
        val evidence = ChargeMath.depletionEvidence(points, reason)
        if (evidence == DepletionEvidence.REPORTED_ONLY && !confirmWithoutStopSample) {
            return "没有停机末点。可以返回补录真实停机记录，或确认仅补记结束原因。"
        }
        val ending = RecordedEnd(reason, System.currentTimeMillis())
        if (!runCatching { save(ending) }.getOrDefault(false)) {
            return "结束原因未能保存，请检查存储空间后重试。"
        }
        endReason = ending.reason
        endReasonRecordedAtMs = ending.savedAtMs
        active = false
        Reminder.cancel(context)
        val manifestUpdated = writeMeta()
        screen = com.mobilelab.chargeaudit.model.Screen.RESULTS
        message = if (evidence == DepletionEvidence.REPORTED_ONLY) {
            "已保存：用户确认耗尽（未记录停机末点）。可导出 ZIP，整程效率留空。"
        } else "结束原因已保存：" + reason.label
        if (!manifestUpdated) message += " 会话目录的清单未能更新；请用结果页重新生成 ZIP。"
        return null
    }

    fun resetSession(deleteFiles: Boolean) {
        Reminder.cancel(context)
        if (deleteFiles) sessionDir?.let { runCatching { it.deleteRecursively() } }
        points = emptyList()
        sessionId = null
        sessionDir = null
        sessionNameplate = null
        fullStartConfirmed = false
        endReason = null
        endReasonRecordedAtMs = null
        startMs = 0L
        nextDueMs = 0L
        active = false
        save()
        screen = com.mobilelab.chargeaudit.model.Screen.HOME
        message = if (deleteFiles) "已删除本会话数据" else "已清空当前会话（保留磁盘文件）"
    }

    fun summary(): ChargeSummary? = ChargeMath.summarize(points, sessionNameplate ?: Nameplate(), endReason, fullStartConfirmed)

    fun exportZip(): File? {
        val id = sessionId ?: return null
        message = null
        val stamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val zip = File(context.cacheDir, "chargeaudit_" + id + "_" + stamp + ".zip")
        return runCatching {
            val staging = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "chargeaudit_export_").toFile()
            try {
                writeCsv(File(staging, "chargeaudit_$id.csv"), points)
                Storage.writeText(File(staging, "manifest.json"), manifest(id).toString(2))
                val json = JSONObject().apply {
                    AuditExport.summaryFields(points.size, summary(), endReason, depletionEvidence, endReasonRecordedAt()).forEach { (key, value) ->
                        put(key, value ?: JSONObject.NULL)
                    }
                }
                Storage.writeText(File(staging, "summary.json"), json.toString(2))
                Storage.zipDirectory(staging, zip)
            } finally {
                staging.deleteRecursively()
            }
        }.onFailure {
            zip.delete()
            message = "导出失败：" + it.message
        }.getOrNull()
    }

    fun csvFile(): File? {
        val file = rawCsvFile() ?: return null
        message = null
        return runCatching { writeCsv(file, points); file }.onFailure { message = "导出 CSV 失败：" + it.message }.getOrNull()
    }

    private fun rawCsvFile(): File? {
        val dir = sessionDir ?: return null
        val id = sessionId ?: return null
        return File(dir, "chargeaudit_$id.csv")
    }

    private fun writeCsv(file: File, samples: List<AuditPoint>) {
        Storage.writeText(file, buildString {
            append(CSV_HEADER).append("\r\n")
            samples.forEach { append(AuditExport.csvRow(it, TimeFmt.isoLocal(it.timestampMs))).append("\r\n") }
        })
    }

    private fun manifest(id: String): JSONObject = JSONObject().apply {
        put("schema", "phonelab.charge/1")
        put("project", "07-charge-bank-efficiency")
        put("sessionId", id)
        put("createdAt", TimeFmt.isoLocal(startMs))
        put("nameplate", sessionNameplate?.toJson() ?: Nameplate().toJson())
        put("points", points.size)
        put("endReason", endReason?.name ?: JSONObject.NULL)
        put("endReasonRecordedAt", endReasonRecordedAt() ?: JSONObject.NULL)
        put("depletionEvidence", depletionEvidence?.exportValue ?: JSONObject.NULL)
        put("fullStartConfirmed", fullStartConfirmed)
        put("fullDischargeConfirmed", summary()?.fullDischarge ?: false)
    }

    private fun writeMeta(): Boolean {
        val dir = sessionDir ?: return false
        val id = sessionId ?: return false
        return runCatching { Storage.writeText(File(dir, "manifest.json"), manifest(id).toString(2)) }.isSuccess
    }
}
