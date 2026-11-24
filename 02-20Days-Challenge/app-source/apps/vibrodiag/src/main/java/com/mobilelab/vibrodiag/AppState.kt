package com.mobilelab.vibrodiag

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.device.collectDeviceSnapshot
import com.mobilelab.core.files.Storage
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.json.JsonObject
import com.mobilelab.vibrodiag.audio.AudioCapability
import com.mobilelab.vibrodiag.audio.AudioProbe
import com.mobilelab.vibrodiag.audio.RecordingEngine
import com.mobilelab.vibrodiag.audio.RecordingRequest
import com.mobilelab.vibrodiag.data.BlindSession
import com.mobilelab.vibrodiag.data.BlindTrialResult
import com.mobilelab.vibrodiag.data.EvaluationReport
import com.mobilelab.vibrodiag.data.Reports
import com.mobilelab.vibrodiag.data.SamplePaths
import com.mobilelab.vibrodiag.data.SessionRepository
import com.mobilelab.vibrodiag.data.SessionRepositoryFactory
import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.data.replaceReport
import com.mobilelab.vibrodiag.ml.FrozenMatlabModel
import com.mobilelab.vibrodiag.ml.LabeledFeatures
import com.mobilelab.vibrodiag.ml.LoocvResult
import com.mobilelab.vibrodiag.ml.ModelEvaluation
import com.mobilelab.vibrodiag.ml.NoiseEvaluationResult
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.Prediction
import com.mobilelab.vibrodiag.model.AccelMode
import com.mobilelab.vibrodiag.model.AppSettings
import com.mobilelab.vibrodiag.model.Condition
import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.model.TrainingSequence
import com.mobilelab.vibrodiag.model.TrainingTarget
import com.mobilelab.vibrodiag.sensor.AccelProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class Screen { Home, Record, BlindTest, Evaluation, Dataset, Settings }

/** Recording lifecycle, owned by the Activity-wide state holder. */
sealed interface RecordingSaveState {
    data object Idle : RecordingSaveState
    data object Saving : RecordingSaveState
    data class Saved(val sample: StoredSample?, val interrupted: Boolean, val error: String?) : RecordingSaveState
    data class Failed(val message: String) : RecordingSaveState
}

data class ActiveRecording(
    val conditionId: String,
    val groupIndex: Int,
    val blindTrialIndex: Int?,
    val startedAtMs: Long,
    val stage: SamplePaths,
    val destination: SamplePaths,
)

/** UI view of the in-flight blind trial (label hidden until reveal). */
data class BlindTrialView(
    val trialIndex: Int,
    val conditionId: String,
    val sample: StoredSample?,
    val prediction: Prediction? = null,
    val revealed: Boolean = false,
    val rawScores: List<Double> = emptyList(),
)

/**
 * Activity scoped state holder: survives orientation changes (the activity
 * declares configChanges) and owns the session, dataset cache and the async
 * evaluation jobs so navigating between screens cannot cancel a running save.
 */
class AppState(val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var repository: SessionRepository by mutableStateOf(SessionRepositoryFactory.openOrCreate(context))
        private set
    var screen by mutableStateOf(Screen.Home)
    private val trainingPrefs = context.getSharedPreferences("vibro_training_navigation", Context.MODE_PRIVATE)
    var trainingConditionId by mutableStateOf<String?>(
        trainingPrefs.getString("condition", null)?.takeIf {
            trainingPrefs.getString("session", null) == repository.sessionId && it in Conditions.ids
        },
    )
        private set
    var samples by mutableStateOf<List<StoredSample>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings.load(context))
        private set
    var audioCapability by mutableStateOf(AudioProbe.probe(context))
        private set
    var accelCapability by mutableStateOf(AccelProbe.probe(context, settings.accelMode))
        private set
    val deviceSnapshot: DeviceSnapshot = collectDeviceSnapshot(context)

    var activeRecording by mutableStateOf<ActiveRecording?>(null)
        private set
    var saveState by mutableStateOf<RecordingSaveState>(RecordingSaveState.Idle)
        private set

    var blindSession by mutableStateOf<BlindSession?>(null)
        private set
    var blindTrial by mutableStateOf<BlindTrialView?>(null)
        private set
    var blindResults by mutableStateOf<List<BlindTrialResult>>(emptyList())
        private set

    var loocv by mutableStateOf<LoocvResult?>(null)
        private set
    var noiseResult by mutableStateOf<NoiseEvaluationResult?>(null)
        private set
    var noiseRunning by mutableStateOf(false)
        private set
    var noiseProgress by mutableStateOf(0f)
        private set
    var evaluationFile by mutableStateOf<File?>(null)
        private set

    var playingFile by mutableStateOf<File?>(null)
        private set
    var exportFile by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val recorder = RecordingEngine(context)

    private var mediaPlayer: MediaPlayer? = null
    private var noiseJob: Job? = null
    private var exportInProgress = false
    private data class BoundBlindModel(val model: FrozenMatlabModel, val digest: String) {
        val signature: String get() = "frozen-matlab:" + model.modelId + ":" + digest
    }

    private val frozenBlindModel: BoundBlindModel? by lazy {
        runCatching {
            val bytes = context.assets.open(FrozenMatlabModel.ASSET_FILENAME).use { it.readBytes() }
            val model = bytes.inputStream().use(FrozenMatlabModel::load)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            BoundBlindModel(model, digest)
        }.onFailure { LabLog.e("AppState", "frozen MATLAB model load failed", it) }.getOrNull()
    }
    val blindModelId: String? get() = frozenBlindModel?.model?.modelId
    val blindModelTrainingSession: String? get() = frozenBlindModel?.model?.trainingSession
    val blindModelTrainingSize: Int? get() = frozenBlindModel?.model?.trainingSamples
    private var blindModelSignature: String? = null
    private var blindRowsVerified = true

    private fun sessionBusy(): Boolean = activeRecording != null || saveState == RecordingSaveState.Saving || exportInProgress

    private fun blockIfBusy(): Boolean {
        if (!sessionBusy()) return false
        showMessage("请等待录音保存或导出完毕")
        return true
    }

    init {
        LabLog.attach(repository.sessionLogFile)
        LabLog.i("AppState", "session " + repository.sessionId + " at " + repository.root.absolutePath)
        refreshSamples()
        writeManifest()
        restoreBlindResults()
        evaluationFile = repository.evaluationFile.takeIf { file ->
            runCatching { file.readText().contains(NoiseEvaluator.PROTOCOL) }.getOrDefault(false)
        }
    }

    // ---------------------------------------------------------------- session

    fun refreshSamples() {
        samples = repository.listSamples()
        if (samples.isNotEmpty()) {
            LabLog.i("AppState", "dataset loaded: " + samples.size + " samples")
        }
    }

    fun countsByCondition(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (condition in Conditions.all) counts[condition.id] = 0
        for (sample in samples) {
            if (sample.interrupted) continue
            counts[sample.conditionId] = (counts[sample.conditionId] ?: 0) + 1
        }
        return counts
    }

    fun usableSamples(): List<StoredSample> = samples.filter { !it.interrupted }

    private fun completedTrainingGroups(): Map<String, Set<Int>> = usableSamples()
        .groupBy { it.conditionId }
        .mapValues { (_, group) -> group.map { it.groupIndex }.toSet() }

    fun nextGroup(conditionId: String): Int? = TrainingSequence.nextGroup(completedTrainingGroups()[conditionId].orEmpty())

    internal fun recommendedTraining(): TrainingTarget? =
        TrainingSequence.recommendation(trainingConditionId, completedTrainingGroups())

    internal fun openTraining(target: TrainingTarget) {
        if (blockIfBusy()) return
        if (target != recommendedTraining()) {
            showMessage("采集进度已变化，请核对首页显示的工况和组号后重新进入")
            return
        }
        selectTrainingCondition(target.conditionId)
        resetRecordingState()
        screen = Screen.Record
    }

    internal fun selectTrainingCondition(id: String) {
        if (sessionBusy() || id !in Conditions.ids) return
        trainingConditionId = id
        trainingPrefs.edit { putString("session", repository.sessionId); putString("condition", id) }
    }

    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings.normalized()
        AppSettings.save(context, settings)
        accelCapability = AccelProbe.probe(context, settings.accelMode)
        writeManifest()
        LabLog.i("AppState", "settings saved duration=" + settings.durationS + "s accel=" + settings.accelMode.id)
    }

    fun refreshCapabilities() {
        audioCapability = AudioProbe.probe(context)
        accelCapability = AccelProbe.probe(context, settings.accelMode)
    }

    private fun writeManifest() {
        runCatching {
            repository.writeManifest(deviceSnapshot, audioCapability, accelCapability, BuildConfig.VERSION_NAME)
        }.onFailure { LabLog.e("AppState", "manifest write failed", it) }
    }

    fun startNewSession() {
        if (blockIfBusy()) return
        clearTrainingEvaluationState()
        stopPlayback()
        recorder.release()
        repository = SessionRepositoryFactory.createNew(context)
        LabLog.attach(repository.sessionLogFile)
        resetSessionState()
        trainingConditionId = null
        trainingPrefs.edit { remove("session"); remove("condition") }
        refreshSamples()
        blindSession = null
        blindTrial = null
        blindResults = emptyList()
        blindModelSignature = null
        writeManifest()
        showMessage("已创建新会话 " + repository.sessionId)
    }

    fun showMessage(text: String) {
        message = text
    }

    fun clearMessage() {
        message = null
    }

    // -------------------------------------------------------------- recording

    fun startRecording(conditionId: String, groupIndex: Int, blindTrialIndex: Int? = null, replaceTraining: Boolean = false): String? {
        if (blockIfBusy()) return "请等待录音保存或导出完毕"
        if (Conditions.byId(conditionId) == null) return "未知工况"
        val captureGroup = if (blindTrialIndex == null) {
            val target = TrainingTarget(conditionId, groupIndex)
            if (conditionId != trainingConditionId || !TrainingSequence.canStart(target, repository.completedGroups(conditionId), replaceTraining)) {
                val message = "工况或组号已变化：未开始录音，也没有覆盖原组。请核对后重试。"
                refreshSamples()
                showMessage(message)
                return message
            }
            groupIndex
        } else {
            if (!blindResultsMatchModel()) return "本轮盲测不属于当前 MATLAB 模型，请先备份旧记录"
            if (blindTrial?.trialIndex != blindTrialIndex || blindTrial?.conditionId != conditionId || blindTrial?.sample != null) {
                return "当前盲测试次已变化或已录制，请刷新页面"
            }
            blindSession?.groupForTrial(blindTrialIndex) ?: return "无效盲测组号"
        }
        val destination = if (blindTrialIndex == null) repository.samplePaths(conditionId, captureGroup)
            else repository.blindSamplePaths(captureGroup, conditionId)
        val stage = runCatching { repository.stageRecording(destination) }.getOrElse {
            val message = "无法准备录音文件：" + (it.message ?: it.javaClass.simpleName)
            showMessage(message)
            return message
        }
        val durationS = if (blindTrialIndex == null) settings.durationS else 15
        val error = runCatching { recorder.start(RecordingRequest(stage.audio, durationS, settings.accelMode)) }
            .getOrElse { throwable ->
                recorder.release()
                "录音启动失败：" + (throwable.message ?: throwable.javaClass.simpleName)
            }
        if (error != null) {
            stage.dir.deleteRecursively()
            LabLog.w("AppState", "recording start failed: " + error)
            showMessage(error)
            return error
        }
        activeRecording = ActiveRecording(conditionId, captureGroup, blindTrialIndex, System.currentTimeMillis(), stage, destination)
        saveState = RecordingSaveState.Idle
        return null
    }


    /**
     * Stops the recorder in the app scope so a lifecycle interruption (incoming
     * call, Home press) still flushes the WAV header, writes the CSV/features and
     * updates the dataset while the screen may already be in the background.
     */
    fun finishRecording(interrupted: Boolean) {
        val active = activeRecording ?: return
        activeRecording = null
        saveState = RecordingSaveState.Saving
        val targetRepository = repository
        val captureSettings = settings
        appScope.launch {
            val output = withContext(Dispatchers.IO) { runCatching { recorder.finish() }.getOrNull() }
            if (output == null) {
                saveState = RecordingSaveState.Failed("录音收尾失败，请重试")
                return@launch
            }
            val incomplete = interrupted || output.interrupted
            if (active.blindTrialIndex == null && !incomplete) clearTrainingEvaluationState()
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    targetRepository.persistRecording(
                        active.conditionId, active.groupIndex, active.stage, active.destination, output,
                        captureSettings, incomplete, blind = active.blindTrialIndex != null,
                    )
                }
            }
            refreshSamples()
            saved.onSuccess { sample ->
                if (active.blindTrialIndex != null && !incomplete) {
                    blindTrial = BlindTrialView(active.blindTrialIndex, active.conditionId, sample)
                }
                saveState = when {
                    active.blindTrialIndex == null -> RecordingSaveState.Saved(sample, incomplete, output.error)
                    incomplete -> RecordingSaveState.Failed("盲测录音已中断，已保留审计文件；请重新采集本组")
                    else -> RecordingSaveState.Idle
                }
                LabLog.i("AppState", "recording persisted " + sample.paths.stem)
            }.onFailure { throwable ->
                LabLog.e("AppState", "persist recording failed", throwable)
                saveState = RecordingSaveState.Failed("保存失败：" + (throwable.message ?: throwable.javaClass.simpleName))
            }
        }
    }

    fun resetRecordingState() {
        if (saveState != RecordingSaveState.Saving) saveState = RecordingSaveState.Idle
    }

    // ------------------------------------------------------------- blind test

    /** A plan belongs to the frozen weights, not to the mutable recording dataset. */
    fun blindResultsMatchModel(): Boolean = blindRowsVerified &&
        frozenBlindModel?.let { it.signature == blindModelSignature } == true

    fun startBlindTest(): String? {
        if (blockIfBusy()) return "请等待录音保存或导出完毕"
        val bound = frozenBlindModel ?: return "无法加载内置 MATLAB 模型，请重新安装新版 APK"
        if (repository.blindDir.exists() || repository.blindCsvFile.exists()) return "已有盲测数据，请先确认重置"
        val session = BlindSession.create()
        val signature = bound.signature
        runCatching { repository.writeBlindPlan(session, signature) }.onFailure {
            LabLog.e("AppState", "blind plan write failed", it)
            return "无法保存盲测计划"
        }
        blindSession = session
        blindModelSignature = signature
        blindRowsVerified = true
        blindResults = emptyList()
        blindTrial = null
        nextBlindTrial()
        return null
    }

    fun resetBlindTest() {
        if (blockIfBusy()) return
        runCatching { repository.clearBlindTestData() }.onFailure {
            LabLog.e("AppState", "blind reset failed", it)
            showMessage("盲测数据未能完整删除：" + it.message)
            restoreBlindResults()
            return
        }
        clearBlindState()
        evaluationFile = null
    }

    private fun clearBlindState() {
        blindSession = null
        blindTrial = null
        blindResults = emptyList()
        blindModelSignature = null
        blindRowsVerified = true
        saveState = RecordingSaveState.Idle
    }

    fun nextBlindTrial() {
        if (sessionBusy()) return
        val session = blindSession ?: return
        if (!blindResultsMatchModel()) return
        val index = blindResults.size
        val conditionId = session.conditionForTrial(index)
        val groupIndex = session.groupForTrial(index)
        blindTrial = if (conditionId != null && groupIndex != null) {
            BlindTrialView(index, conditionId, repository.readBlindSample(groupIndex, conditionId))
        } else null
        saveState = RecordingSaveState.Idle
    }


    fun predictBlindTrial() {
        if (blockIfBusy()) return
        if (!blindResultsMatchModel()) {
            showMessage("历史盲测不是当前 MATLAB 模型，不能混合预测")
            return
        }
        val trial = blindTrial ?: return
        val sample = trial.sample ?: return
        val model = frozenBlindModel?.model ?: return
        val (prediction, scores) = runCatching {
            model.predict(sample.featureArray) to model.predictScores(sample.featureArray).toList()
        }.getOrElse {
            LabLog.e("AppState", "frozen MATLAB inference failed", it)
            showMessage("预测失败：本组特征无效")
            return
        }
        blindTrial = trial.copy(prediction = prediction, rawScores = scores)
    }

    fun revealBlindTrial() {
        if (blockIfBusy() || !blindResultsMatchModel()) return
        val trial = blindTrial ?: return
        if (trial.revealed || trial.trialIndex != blindResults.size) return
        val prediction = trial.prediction ?: return
        val sample = trial.sample ?: return
        val bound = frozenBlindModel ?: return
        if (trial.rawScores.size != bound.model.conditions.size) return
        val condition = Conditions.byId(trial.conditionId) ?: return
        val predictedCondition = Conditions.byId(prediction.label)
        val result = BlindTrialResult(
            trialIndex = trial.trialIndex,
            trueConditionId = condition.id,
            trueConditionLabel = condition.label,
            predictedConditionId = prediction.label,
            predictedConditionLabel = predictedCondition?.label ?: prediction.label,
            confidence = Double.NaN, // Regression/ranking scores are not calibrated probabilities.
            featuresRelativePath = repository.relativePath(sample.paths.features),
            recordedAtMs = sample.recordedAtMs,
        )
        val updated = blindResults + result
        runCatching {
            repository.invalidateEvaluationReport()
            val details = JsonObject()
                .put("schema", "phonelab.vibro.frozen_prediction/1")
                .put("model", bound.model.modelId)
                .put("asset_sha256", bound.digest)
                .put("training_session", bound.model.trainingSession)
                .put("training_samples", bound.model.trainingSamples)
                .put("training_backend", "MATLAB CUDA")
                .put("inference_backend", "Android CPU")
                .put("trial_index", trial.trialIndex)
                .put("features_file", result.featuresRelativePath)
                .put("predicted_condition", prediction.label)
                .put("true_condition", condition.id)
                .put("score_labels", bound.model.conditions)
                .put("raw_scores", trial.rawScores)
                .put("top1_minus_top2", prediction.confidence)
                .put("score_note", "raw classifier scores and margin, not probabilities")
            replaceReport(File(sample.paths.dir, "matlab_prediction.json")) { it.writeText(details.toJson()) }
            Reports.writeBlindTest(repository.blindCsvFile, repository.sessionId, updated, bound.model.modelId)
        }.onFailure {
            LabLog.e("AppState", "blind csv write failed", it)
            showMessage("盲测结果未保存，请再次尝试揭示")
            return
        }
        evaluationFile = null
        blindResults = updated
        blindTrial = trial.copy(revealed = true)
        LabLog.i(
            "AppState",
            "blind trial " + trial.trialIndex + " true=" + condition.id + " predicted=" + prediction.label +
                " correct=" + result.correct + " model=" + bound.model.modelId + " score_margin=" + prediction.confidence,
        )
    }

    fun blindAccuracy(): Double =
        if (blindResults.isEmpty()) 0.0 else blindResults.count { it.correct }.toDouble() / blindResults.size

    fun blindConfusion(): List<List<Int>> = ModelEvaluation.confusion(
        Conditions.ids,
        blindResults.map { it.trueConditionId },
        blindResults.map { it.predictedConditionId },
    )

    fun blindIsComplete(): Boolean {
        val session = blindSession ?: return false
        return blindResults.size >= session.total
    }

    private fun restoreBlindResults() {
        val rows = Reports.readBlindRows(repository.blindCsvFile)
        val plan = repository.readBlindPlan()
        blindSession = plan?.second
        blindModelSignature = plan?.first
        blindTrial = null
        blindRowsVerified = if (!repository.blindCsvFile.isFile) true else {
            val model = frozenBlindModel?.model
            rows.isNotEmpty() && model != null && rows.size <= (blindSession?.total ?: 0) &&
                rows.withIndex().all { (i, row) ->
                    row.trialIndex == i && row.model == model.modelId &&
                        row.trueConditionId == blindSession?.conditionForTrial(i) &&
                        row.predictedConditionId in model.conditions &&
                        row.correct == (row.trueConditionId == row.predictedConditionId)
                }
        }
        blindResults = rows.mapNotNull { row ->
            val condition = Conditions.byId(row.trueConditionId) ?: return@mapNotNull null
            BlindTrialResult(
                trialIndex = row.trialIndex,
                trueConditionId = condition.id,
                trueConditionLabel = condition.label,
                predictedConditionId = row.predictedConditionId,
                predictedConditionLabel = Conditions.labelOf(row.predictedConditionId),
                confidence = row.confidence,
                featuresRelativePath = row.featuresRelativePath,
                recordedAtMs = row.recordedAtMs,
            )
        }
        if (blindSession != null && blindResultsMatchModel()) nextBlindTrial()
        LabLog.i("AppState", "restored " + blindResults.size + " blind trials from CSV")
    }

    // ------------------------------------------------------------- evaluation

    fun runLoocv(): LoocvResult? {
        if (blockIfBusy()) return null
        val items = usableSamples().map { LabeledFeatures(it.conditionId, it.featureArray) }
        if (items.size < 2) {
            showMessage("样本不足，至少需要 2 组数据")
            return null
        }
        val result = ModelEvaluation.loocv(items, Conditions.ids)
        loocv = result
        LabLog.i("AppState", "LOOCV accuracy=" + result.accuracy + " (" + result.correct + "/" + result.total + ")")
        return result
    }

    fun runNoiseEvaluation(noiseTypes: List<NoiseType>, snrLevels: List<Int>) {
        if (noiseTypes.isEmpty() || snrLevels.isEmpty()) return
        if (noiseRunning || blockIfBusy()) return
        val usable = usableSamples()
        if (usable.size < 2) {
            showMessage("抗噪留一评估至少需要 2 组数据")
            return
        }
        val targetRepository = repository
        noiseRunning = true
        noiseProgress = 0f
        noiseJob = appScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    NoiseEvaluator.evaluate(
                        samples = usable,
                        conditionIds = Conditions.ids,
                        snrLevels = snrLevels,
                        noiseTypes = noiseTypes,
                        onProgress = { noiseProgress = it },
                        checkCancelled = { coroutineContext.ensureActive() },
                    )
                }
                // Publish on Main so capture/deletion/session changes cannot interleave with the commit.
                if (targetRepository !== repository) return@launch
                targetRepository.invalidateEvaluationReport()
                evaluationFile = null
                Reports.writeNoiseEval(targetRepository.noiseCsvFile, targetRepository.sessionId, result.rows, NoiseEvaluator.REPORT_MODEL)
                noiseResult = result
                LabLog.i("AppState", "noise evaluation finished rows=" + result.rows.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                LabLog.e("AppState", "noise evaluation failed", throwable)
                showMessage("抗噪评估失败：" + (throwable.message ?: throwable.javaClass.simpleName))
            } finally {
                noiseRunning = false
                noiseProgress = 0f
            }
        }
    }

    fun cancelNoiseEvaluation() {
        noiseJob?.cancel()
        showMessage("正在停止抗噪评估…")
    }


    fun readCurrentNoiseRows() = Reports.readNoiseRows(repository.noiseCsvFile, expectedModel = NoiseEvaluator.REPORT_MODEL)

    fun generateEvaluationReport(notes: String): File? {
        if (blockIfBusy()) return null
        val usable = usableSamples()
        if (usable.isEmpty()) {
            showMessage("数据集为空，无法生成评估报告")
            return null
        }
        val loocvResult = loocv ?: runLoocv()
        val blindRows = if (blindResultsMatchModel()) Reports.readBlindRows(repository.blindCsvFile) else emptyList()
        val noise = noiseResult ?: run {
            val rows = readCurrentNoiseRows()
            if (rows.isEmpty()) null else NoiseEvaluationResult(
                rows,
                NoiseEvaluator.DEFAULT_SNR_LEVELS.filter { level -> rows.any { it.snrDb == level } },
                NoiseEvaluator.DEFAULT_NOISE_TYPES.filter { type -> rows.any { it.noiseType == type } },
            )
        }
        val conditionSamples = LinkedHashMap<String, Int>()
        for (condition in Conditions.all) {
            conditionSamples[condition.id] = usable.count { it.conditionId == condition.id }
        }
        val json = EvaluationReport.build(
            sessionId = repository.sessionId,
            generatedAtMs = System.currentTimeMillis(),
            trainingSamples = usable.size,
            loocv = loocvResult,
            blindRows = blindRows,
            noise = noise,
            conditionSamples = conditionSamples,
            notes = notes,
        )
        val file = repository.evaluationFile
        return runCatching {
            EvaluationReport.write(file, json)
            evaluationFile = file
            LabLog.i("AppState", "evaluation report written " + file.absolutePath)
            file
        }.onFailure {
            LabLog.e("AppState", "evaluation report write failed", it)
            showMessage("评估报告写入失败")
        }.getOrNull()
    }

    fun evaluationReportText(): String =
        runCatching { repository.evaluationFile.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    // ---------------------------------------------------------------- dataset

    fun deleteConditionSamples(conditionId: String) = deleteFromDataset {
        val removed = repository.deleteConditionSamples(conditionId)
        "已删除 " + Conditions.labelOf(conditionId) + " 的 " + removed + " 组"
    }

    fun deleteAllSamples() = deleteFromDataset {
        val removed = repository.deleteAllSamples()
        clearBlindState()
        "已删除全部 " + removed + " 组数据与派生评估"
    }

    private fun clearTrainingEvaluationState() {
        noiseJob?.cancel()
        loocv = null
        noiseResult = null
        noiseProgress = 0f
        evaluationFile = null
        exportFile = null
    }

    fun deleteSample(sample: StoredSample) = deleteFromDataset {
        repository.deleteSample(sample)
        "已删除 " + sample.paths.stem
    }

    private fun deleteFromDataset(delete: () -> String) {
        if (blockIfBusy()) return
        clearTrainingEvaluationState()
        stopPlayback()
        runCatching(delete).onSuccess { showMessage(it) }.onFailure {
            LabLog.e("AppState", "dataset deletion failed", it)
            restoreBlindResults()
            showMessage("数据未能完整删除：" + it.message)
        }
        refreshSamples()
    }


    fun togglePlayback(file: File) {
        if (playingFile == file) {
            stopPlayback()
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { stopPlayback() }
            mediaPlayer = player
            playingFile = file
        }.onFailure { throwable ->
            LabLog.e("AppState", "playback failed " + file.absolutePath, throwable)
            runCatching { player.release() }
            showMessage("无法播放该录音")
        }
    }

    fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        playingFile = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
    }

    // ----------------------------------------------------------------- export

    fun createSessionZipAsync(onDone: (File?) -> Unit = {}) {
        if (blockIfBusy()) return
        if (noiseRunning) {
            showMessage("请等待抗噪评估完成后再导出")
            return
        }
        val targetRepository = repository
        exportInProgress = true
        appScope.launch {
            val zip = try {
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "vibrodiag_" + targetRepository.sessionId + ".zip")
                    Storage.zipDirectory(targetRepository.root, file)
                }
            } catch (error: Exception) {
                LabLog.e("AppState", "zip export failed", error)
                showMessage("导出失败：" + error.message)
                null
            } finally {
                exportInProgress = false
            }
            exportFile = zip
            onDone(zip)
        }
    }


    fun clearExportFile() {
        exportFile = null
    }

    // ------------------------------------------------------------- lifecycle

    fun dispose() {
        stopPlayback()
        noiseJob?.cancel()
        // If a lifecycle callback already started finalization, let it close the writer.
        if (activeRecording != null) finishRecording(true)
        else if (saveState != RecordingSaveState.Saving) recorder.release()
    }

    private fun resetSessionState() {
        loocv = null
        noiseResult = null
        noiseProgress = 0f
        evaluationFile = null
        exportFile = null
        playingFile = null
        saveState = RecordingSaveState.Idle
        activeRecording = null
        screen = Screen.Home
    }
}
