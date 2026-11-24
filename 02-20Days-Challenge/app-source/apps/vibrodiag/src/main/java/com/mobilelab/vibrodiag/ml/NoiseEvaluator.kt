package com.mobilelab.vibrodiag.ml

import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.features.FeatureExtractor
import com.mobilelab.vibrodiag.features.FeatureVector
import com.mobilelab.vibrodiag.features.NoiseGenerator
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.features.WavIo

/** One aggregated (noise type, SNR, condition) cell of the robustness table. */
data class NoiseRow(
    val noiseType: NoiseType,
    val snrDb: Int,
    val conditionId: String,
    val trials: Int,
    val correct: Int,
) {
    val accuracy: Double get() = if (trials > 0) correct.toDouble() / trials else 0.0
}

data class NoiseEvaluationResult(
    val rows: List<NoiseRow>,
    val snrLevels: List<Int>,
    val noiseTypes: List<NoiseType>,
) {
    /** Weighted accuracy over all conditions for one noise type / SNR pair. */
    fun accuracy(noiseType: NoiseType, snrDb: Int): Double {
        val matching = rows.filter { it.noiseType == noiseType && it.snrDb == snrDb }
        val trials = matching.sumOf { it.trials }
        val correct = matching.sumOf { it.correct }
        return if (trials > 0) correct.toDouble() / trials else Double.NaN
    }

    fun cell(noiseType: NoiseType, snrDb: Int, conditionId: String): NoiseRow? =
        rows.firstOrNull { it.noiseType == noiseType && it.snrDb == snrDb && it.conditionId == conditionId }
}

/**
 * Re-extracts audio features from every clean recording after mixing in white or
 * pink noise at the requested SNR. The accelerometer part of the feature vector
 * is unchanged because the noise is purely acoustic. Each noisy test take is
 * assessed against the other clean takes: its own clean vector never enters
 * the z-score fit or kNN training fold.
 */
object NoiseEvaluator {

    const val DEFAULT_SEED_BASE = 20261118L
    const val PROTOCOL = "leave-one-out; train clean, test noisy"
    const val REPORT_MODEL = ModelSpec.DESCRIPTOR + "; " + PROTOCOL
    val DEFAULT_SNR_LEVELS: List<Int> = listOf(20, 10, 5, 0, -5)
    val DEFAULT_NOISE_TYPES: List<NoiseType> = listOf(NoiseType.WHITE, NoiseType.PINK)

    fun evaluate(
        samples: List<StoredSample>,
        conditionIds: List<String>,
        snrLevels: List<Int> = DEFAULT_SNR_LEVELS,
        noiseTypes: List<NoiseType> = DEFAULT_NOISE_TYPES,
        seedBase: Long = DEFAULT_SEED_BASE,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): NoiseEvaluationResult {
        require(samples.size >= 2) { "抗噪留一评估至少需要 2 组" }
        val items = samples.map { LabeledFeatures(it.conditionId, it.featureArray) }
        val counts = LinkedHashMap<String, IntArray>()
        for (index in samples.indices) {
            checkCancelled()
            val sample = samples[index]
            val classifier = ModelEvaluation.fitHoldout(items, index)
            val wav = WavIo.read(sample.paths.audio)
            check(wav.samples.isNotEmpty()) { "录音文件为空：" + sample.paths.stem }
            val signal = DoubleArray(wav.samples.size) { wav.samples[it] / 32768.0 }
            check(signal.any { it != 0.0 }) { "静音无法按目标信噪比加噪：" + sample.paths.stem }
            for (noiseType in noiseTypes) {
                for (snrDb in snrLevels) {
                    checkCancelled()
                    val seed = seedBase + index * 7919L + snrDb * 104729L + noiseType.ordinal
                    val mixed = NoiseGenerator.mix(signal, noiseType, snrDb.toDouble(), seed)
                    val audio = FeatureExtractor.extractAudioNormalized(mixed, wav.sampleRate)
                    val merged = FeatureVector.merge(audio, sample.features)
                    val prediction = checkNotNull(classifier.predict(FeatureVector.toArray(merged)))
                    val key = key(noiseType, snrDb, sample.conditionId)
                    val entry = counts.getOrPut(key) { IntArray(2) }
                    entry[0] += 1
                    if (prediction.label == sample.conditionId) entry[1] += 1
                }
            }
            onProgress((index + 1).toFloat() / samples.size)
        }
        val rows = ArrayList<NoiseRow>()
        for (snrDb in snrLevels) {
            for (noiseType in noiseTypes) {
                for (conditionId in conditionIds) {
                    val entry = counts[key(noiseType, snrDb, conditionId)] ?: continue
                    if (entry[0] <= 0) continue
                    rows.add(NoiseRow(noiseType, snrDb, conditionId, entry[0], entry[1]))
                }
            }
        }
        checkCancelled()
        return NoiseEvaluationResult(rows, snrLevels, noiseTypes)
    }

    private fun key(noiseType: NoiseType, snrDb: Int, conditionId: String): String =
        noiseType.id + "|" + snrDb + "|" + conditionId
}
