package com.mobilelab.vibrodiag.data

import android.content.Context
import androidx.core.content.edit
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.device.deviceManifestJson
import com.mobilelab.core.files.Storage
import com.mobilelab.core.json.JsonObject
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.vibrodiag.audio.AudioCapability
import com.mobilelab.vibrodiag.audio.RecordingOutput
import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.features.FeaturePipeline
import com.mobilelab.vibrodiag.model.AppSettings
import com.mobilelab.vibrodiag.model.Condition
import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.sensor.AccelCapability
import java.io.File
import java.util.Locale

/**
 * Owns the on-device experiment directory for one session.
 *
 * Layout (DATA_DICTIONARY 3.7):
 *   experiments/vibrodiag/<stamp>_<sessionId>/
 *     manifest.json
 *     <conditionId>/g<NN>/g<NN>_<conditionId>_{audio.wav,accel.csv,features.csv,meta.json}
 *     dataset_index.csv
 *     blind_test_<sessionId>.csv
 *     noise_eval_<sessionId>.csv
 *     evaluation_<sessionId>.json
 *     session.log
 *     photos/
 *     blind/                 # blind test recordings (not part of the 75-sample dataset)
 */
class SessionRepository internal constructor(
    val root: File,
    val sessionId: String,
) {

    val datasetIndexFile: File get() = File(root, "dataset_index.csv")
    val blindCsvFile: File get() = File(root, "blind_test_" + sessionId + ".csv")
    val noiseCsvFile: File get() = File(root, "noise_eval_" + sessionId + ".csv")
    val evaluationFile: File get() = File(root, "evaluation_" + sessionId + ".json")
    val sessionLogFile: File get() = File(root, "session.log")
    val photosDir: File get() = File(root, "photos")
    val blindDir: File get() = File(root, "blind")

    fun conditionDir(conditionId: String): File = File(root, conditionId)

    fun sampleDir(conditionId: String, groupIndex: Int): File =
        File(conditionDir(conditionId), String.format(Locale.US, "g%02d", groupIndex))

    fun groupStem(groupIndex: Int, conditionId: String): String =
        String.format(Locale.US, "g%02d_%s", groupIndex, conditionId)

    fun samplePaths(conditionId: String, groupIndex: Int): SamplePaths =
        SamplePaths.of(sampleDir(conditionId, groupIndex), groupStem(groupIndex, conditionId))

    fun blindSamplePaths(groupIndex: Int, conditionId: String): SamplePaths {
        val group = String.format(Locale.US, "b%02d", groupIndex)
        return SamplePaths.of(File(blindDir, "$conditionId/$group"), "${group}_$conditionId")
    }

    private val stagingDir: File get() = File(root.parentFile, ".capture/$sessionId")
    private val interruptedDir: File get() = File(root, "interrupted")

    fun stageRecording(destination: SamplePaths): SamplePaths = CaptureFiles.stage(stagingDir, destination)

    fun relativePath(file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    fun interruptedMarker(paths: SamplePaths): File = File(paths.dir, ".interrupted")

    fun ensureLayout() {
        root.mkdirs()
        photosDir.mkdirs()
        // A killed process may have stopped between the two directory renames.
        for (condition in Conditions.all) {
            for (group in 1..Conditions.TARGET_GROUPS) CaptureFiles.recover(samplePaths(condition.id, group))
            for (group in 1..BlindSession.TRIALS_PER_CONDITION) CaptureFiles.recover(blindSamplePaths(group, condition.id))
        }
        writeDatasetIndex()
    }

    fun listSamples(): List<StoredSample> = Conditions.all.flatMap { condition ->
        val numbered = (1..Conditions.TARGET_GROUPS).mapNotNull { group ->
            readSample(condition, group, samplePaths(condition.id, group))
        }
        val audited = File(interruptedDir, condition.id).listFiles().orEmpty().mapNotNull { dir ->
            val group = dir.name.take(3).removePrefix("g").toIntOrNull() ?: return@mapNotNull null
            readSample(condition, group, SamplePaths.of(dir, groupStem(group, condition.id)), audit = true)
        }
        (numbered + audited).sortedBy { it.groupIndex }
    }

    private fun readSample(condition: Condition, group: Int, paths: SamplePaths, audit: Boolean = false): StoredSample? {
        if (!paths.audio.isFile || paths.audio.length() <= 44L || !paths.accel.isFile || !paths.meta.isFile) return null
        val table = CsvReader.readTable(paths.features) ?: return null
        val row = table.rows.singleOrNull() ?: return null
        if (table.header != FeatureNames.csvHeader || row.size != table.header.size) return null
        val features = FeatureNames.numeric.associateWith { table.double(row, it) }
        if (FeatureNames.audioTime.none { features[it]?.isFinite() == true }) return null
        return StoredSample(
            conditionId = condition.id,
            conditionLabel = condition.label,
            groupIndex = group,
            paths = paths,
            features = features,
            durationS = table.double(row, "duration_s"),
            recordedAtMs = table.long(row, "recorded_at_ms"),
            interrupted = audit || interruptedMarker(paths).exists(),
        )
    }

    private fun isComplete(condition: Condition, group: Int): Boolean =
        readSample(condition, group, samplePaths(condition.id, group))?.interrupted == false

    internal fun completedGroups(conditionId: String): Set<Int> {
        val condition = Conditions.byId(conditionId) ?: return emptySet()
        return (1..Conditions.TARGET_GROUPS).filterTo(linkedSetOf()) { isComplete(condition, it) }
    }

    fun countComplete(conditionId: String): Int = completedGroups(conditionId).size

    fun collectedCounts(): Map<String, Int> = Conditions.all.associate { it.id to countComplete(it.id) }

    fun invalidateTrainingReports() {
        deleteFileOrThrow(evaluationFile)
        deleteFileOrThrow(noiseCsvFile)
    }

    fun invalidateEvaluationReport() = deleteFileOrThrow(evaluationFile)

    private fun deleteFileOrThrow(file: File) {
        check(!file.exists() || file.delete()) { "无法删除：" + file.name }
    }

    private fun deleteTreeOrThrow(dir: File) {
        check(dir.deleteRecursively()) { "无法完整删除：" + dir.name }
    }

    fun deleteSample(sample: StoredSample) {
        if (!sample.interrupted) invalidateTrainingReports()
        try {
            CaptureFiles.recover(sample.paths)
            deleteTreeOrThrow(sample.paths.dir)
        } finally {
            writeDatasetIndex()
        }
        LabLog.i(TAG, "deleted sample " + sample.paths.stem)
    }

    private fun deleteConditionFiles(conditionId: String): Int {
        val groups = (1..Conditions.TARGET_GROUPS).map { sampleDir(conditionId, it) }.count { it.isDirectory }
        deleteTreeOrThrow(conditionDir(conditionId))
        deleteTreeOrThrow(File(interruptedDir, conditionId))
        return groups
    }

    fun deleteConditionSamples(conditionId: String): Int {
        invalidateTrainingReports()
        try {
            return deleteConditionFiles(conditionId)
        } finally {
            writeDatasetIndex()
        }
    }

    /** Deletes acquisitions and all derived reports, retaining manifest/session.log. */
    fun deleteAllSamples(): Int {
        invalidateTrainingReports()
        try {
            val removed = Conditions.all.sumOf { deleteConditionFiles(it.id) }
            clearBlindTestData()
            deleteTreeOrThrow(stagingDir)
            return removed
        } finally {
            writeDatasetIndex()
        }
    }

    fun writeDatasetIndex() {
        val rows = Conditions.all.map { it.id to it.label }
        Reports.writeDatasetIndex(datasetIndexFile, sessionId, rows, collectedCounts(), Conditions.TARGET_GROUPS)
    }


    fun writeManifest(
        snapshot: DeviceSnapshot,
        audio: AudioCapability,
        accel: AccelCapability,
        appVersion: String,
    ) {
        val conditions = Conditions.all.map { condition ->
            JsonObject().put("id", condition.id).put("label", condition.label)
        }
        val extras = mapOf<String, Any?>(
            "sessionId" to sessionId,
            "conditions" to conditions,
            "targetGroups" to Conditions.TARGET_GROUPS,
            "audio" to JsonObject()
                .put("appSampleRate", audio.sampleRate)
                .put("channels", 1)
                .put("bits", 16)
                .put("resolvedSource", audio.sourceLabel)
                .put("unprocessedSupported", audio.unprocessedSupported)
                .put("micSupported", audio.micSupported)
                .put("minBufferBytes", audio.minBufferBytes)
                .put("outputSampleRateHz", audio.outputSampleRateHz)
                .put("note", audio.note),
            "accel" to JsonObject()
                .put("sensor", accel.sensorName)
                .put("vendor", accel.vendor)
                .put("minDelayUs", accel.minDelayUs)
                .put("maxDelayUs", accel.maxDelayUs)
                .put("maxRateHz", accel.maxRateHz)
                .put("supports200Hz", accel.supports200Hz)
                .put("requestedMode", accel.requestedMode.id)
                .put("resolvedPeriodUs", accel.resolvedPeriodUs)
                .put("resolvedRateHz", accel.resolvedRateHz),
        )
        Storage.writeText(
            File(root, "manifest.json"),
            deviceManifestJson("VibroDiag", appVersion, "03-vibro-diag", snapshot, extras),
        )
    }

    /** Persists the bundle off to the side before publishing it; interrupted takes are audit-only. */
    fun persistRecording(
        conditionId: String,
        groupIndex: Int,
        stage: SamplePaths,
        destination: SamplePaths,
        output: RecordingOutput,
        settings: AppSettings,
        interrupted: Boolean,
        blind: Boolean,
    ): StoredSample {
        val condition = Conditions.byId(conditionId)
            ?: throw IllegalArgumentException("未知工况 " + conditionId)
        if (!stage.audio.isFile || stage.audio.length() <= 44L) {
            stage.dir.deleteRecursively()
            throw IllegalStateException("录音文件为空，无法保存")
        }
        check(output.wavFile == stage.audio) { "录音文件与当前采集不匹配" }
        AccelCsv.write(stage.accel, output.accelSamples, output.baseNanos, output.accelRateHz)
        val features = FeaturePipeline.extractFromFiles(stage.audio, stage.accel)
        FeaturesCsv.write(
            file = stage.features,
            features = features,
            conditionId = conditionId,
            conditionLabel = condition.label,
            groupIndex = groupIndex,
            fileStem = stage.stem,
            recordedAtMs = output.startedAtMs,
            durationS = output.durationS,
        )
        writeMeta(stage, condition, groupIndex, output, features, settings, interrupted, blind)
        val published = if (interrupted) {
            val auditRoot = if (blind) File(blindDir, "interrupted") else interruptedDir
            val parent = File(auditRoot, conditionId)
            parent.mkdirs()
            SamplePaths.of(File(parent, destination.dir.name + "-" + stage.dir.name), stage.stem)
        } else {
            // Invalidate on disk before committing so a killed process cannot reopen stale reports.
            if (!blind) invalidateTrainingReports()
            destination
        }
        CaptureFiles.publish(stage, published)
        if (!blind) writeDatasetIndex()
        LabLog.i(TAG, "saved " + published.stem + " interrupted=" + interrupted + " audio=" + output.durationS + "s")
        return checkNotNull(readSample(condition, groupIndex, published, audit = interrupted))
    }

    private val blindPlanFile: File get() = File(blindDir, "plan.txt")

    fun writeBlindPlan(session: BlindSession, signature: String) = replaceReport(blindPlanFile) { file ->
        file.writeText((listOf(signature) + session.trials).joinToString("\n", postfix = "\n"))
    }

    fun readBlindPlan(): Pair<String, BlindSession>? = runCatching {
        val lines = blindPlanFile.readLines()
        val session = BlindSession.fromTrials(lines.drop(1)) ?: return null
        lines.first() to session
    }.getOrNull()

    fun readBlindSample(groupIndex: Int, conditionId: String): StoredSample? = Conditions.byId(conditionId)?.let {
        readSample(it, groupIndex, blindSamplePaths(groupIndex, conditionId))
    }

    fun clearBlindTestData() {
        invalidateEvaluationReport()
        deleteTreeOrThrow(blindDir)
        deleteFileOrThrow(blindCsvFile)
    }


    private fun writeMeta(
        paths: SamplePaths,
        condition: Condition,
        groupIndex: Int,
        output: RecordingOutput,
        features: Map<String, Double>,
        settings: AppSettings,
        interrupted: Boolean,
        blind: Boolean,
    ) {
        val audio = JsonObject()
            .put("sample_rate", output.audioSampleRate)
            .put("channels", 1)
            .put("bits", 16)
            .put("source", output.audioSourceLabel)
            .put("file", paths.audio.name)
            .put("rms_dbfs", features["audio_rms_dbfs"])
            .put("peak_dbfs", features["audio_peak_dbfs"])
        val accel = JsonObject()
            .put("sample_rate_hz", output.accelRateHz)
            .put("file", paths.accel.name)
            .put("rms_ms2", features["accel_rms"])
            .put("peak_ms2", features["accel_peak"])
            .put("samples", output.accelSamples.size)
        val meta = JsonObject()
            .put("schema", "phonelab.vibro/1")
            .put("session_id", sessionId)
            .put("condition_id", condition.id)
            .put("condition_label", condition.label)
            .put("group_index", groupIndex)
            .put("recorded_at_ms", output.startedAtMs)
            .put("duration_s", output.durationS)
            .put("audio", audio)
            .put("accel", accel)
            .put("placement", settings.placement)
            .put("device_distance_cm", settings.distanceCm)
            .put("noise_condition", settings.noiseCondition)
            .put("noise_spl_db", null)
            .put("operator", settings.operator)
            .put("notes", output.error ?: "")
            .put("interrupted", interrupted)
            // Blind-test recordings live in blind/ and are NOT part of the 75-sample
            // dataset; analysis scripts must skip them (rglob meta would see them).
            .put("blind_trial", blind)
            .put("exclude_from_dataset", blind || interrupted)
        Storage.writeText(paths.meta, meta.toJson())
    }

    companion object {
        private const val TAG = "SessionRepository"
    }
}

/** Creates/opens the app's working session and restores it across process restarts. */
object SessionRepositoryFactory {

    const val APP_TAG = "vibrodiag"
    private const val PREFS = "vibrodiag_session"
    private const val KEY_DIR = "session_dir"
    private val SESSION_ID = Regex("VD-\\d{8}-\\d{3}")

    fun openOrCreate(context: Context): SessionRepository {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DIR, null)
        if (!stored.isNullOrBlank()) {
            val dir = File(stored)
            val sessionId = extractSessionId(dir.name)
            if (dir.isDirectory && sessionId != null) {
                LabLog.attach(File(dir, "session.log"))
                val repository = SessionRepository(dir, sessionId)
                repository.ensureLayout()
                LabLog.i("Session", "opened " + sessionId + " at " + dir.absolutePath)
                return repository
            }
        }
        return createNew(app)
    }

    fun createNew(context: Context): SessionRepository {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val day = TimeFmt.fileStamp(now).substring(0, 8)
        val appRoot = Storage.appRoot(app, APP_TAG)
        var sequence = (appRoot.listFiles()?.count { it.name.contains("_VD-") } ?: 0) + 1
        var sessionId: String
        var dir: File
        while (true) {
            sessionId = "VD-" + day + "-" + String.format(Locale.US, "%03d", sequence)
            val clash = appRoot.listFiles()?.any { it.name.contains(sessionId) } == true
            if (!clash) {
                dir = Storage.newSessionDir(app, APP_TAG, sessionId)
                break
            }
            sequence++
        }
        dir.mkdirs()
        File(dir, "photos").mkdirs()
        val repository = SessionRepository(dir, sessionId)
        repository.ensureLayout()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DIR, dir.absolutePath)
        }
        LabLog.attach(File(dir, "session.log"))
        LabLog.i("Session", "created " + sessionId + " at " + dir.absolutePath)
        return repository
    }

    fun extractSessionId(dirName: String): String? = SESSION_ID.find(dirName)?.value
}
