package com.mobilelab.vibrodiag.ml

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.model.Conditions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

class FrozenMatlabModelTest {
    @Test
    fun all75TrainingRowsMatchOriginalMatlabLabelsAndRawScores() {
        val model = assetFile().inputStream().use(FrozenMatlabModel::load)
        assertEquals("matlab-rbf-ridge-VD-20261122-002-v1", model.modelId)
        assertEquals("VD-20261122-002", model.trainingSession)
        assertEquals(75, model.trainingSamples)
        assertEquals(Conditions.ids, model.conditions)

        val reference = checkNotNull(javaClass.getResourceAsStream("/matlab_predictions.tsv")) {
            "Missing MATLAB prediction fixture"
        }.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        assertEquals(76, reference.size)
        val featureCount = FeatureNames.count
        val labelColumn = featureCount
        val scoresStart = labelColumn + 1
        val expectedHeader = FeatureNames.numeric + listOf("expected_label") + model.conditions
        assertEquals(expectedHeader, reference.first().removePrefix("\uFEFF").split('\t'))

        var maxScoreError = 0.0
        var missingCells = 0
        for ((index, line) in reference.drop(1).withIndex()) {
            val row = index + 2
            val cells = line.split('\t')
            assertEquals("TSV line $row column count", expectedHeader.size, cells.size)
            val features = DoubleArray(featureCount) { j ->
                if (cells[j].isEmpty()) {
                    missingCells++
                    Double.NaN
                } else {
                    cells[j].toDouble().also { assertTrue("TSV line $row feature $j", it.isFinite()) }
                }
            }
            val prediction = model.predict(features)
            assertEquals("TSV line $row label", cells[labelColumn], prediction.label)
            val scores = model.predictScores(features)
            assertEquals(model.conditions.size, scores.size)
            val expected = DoubleArray(scores.size) { c ->
                cells[scoresStart + c].toDouble().also { assertTrue("TSV line $row score $c", it.isFinite()) }
            }
            for ((c, score) in scores.withIndex()) {
                assertEquals("TSV line $row score for ${model.conditions[c]}", expected[c], score, SCORE_TOLERANCE)
                maxScoreError = maxOf(maxScoreError, abs(expected[c] - score))
            }
            val sortedScores = expected.sortedDescending()
            assertEquals("TSV line $row raw margin", sortedScores[0] - sortedScores[1], prediction.confidence, 2 * SCORE_TOLERANCE)
            assertTrue(prediction.neighborLabels.isEmpty() && prediction.distances.isEmpty())
        }
        assertTrue("Expected to exercise missing TSV cells", missingCells > 0)
        println("Frozen MATLAB parity: 75/75 labels, 375 raw scores, maximum absolute error=$maxScoreError")
    }

    @Test
    fun imputationAndTieBreakingFollowFrozenContract() {
        val model = load(syntheticModel())
        val withMissing = DoubleArray(FeatureNames.count) { Double.NaN }.apply { this[0] = 1.0 }
        val filled = withMissing.copyOf().apply { this[1] = 7.0 }
        val changed = withMissing.copyOf().apply { this[1] = 3.0 }
        val unretained = filled.copyOf().apply { this[lastIndex] = 99.0 }
        val reference = doubleArrayOf(1.25, 1.25, -0.5, 0.0, 0.25)
        assertArrayEquals(reference, model.predictScores(withMissing), 1e-12)
        assertArrayEquals(reference, model.predictScores(filled), 1e-12)
        assertArrayEquals(reference, model.predictScores(unretained), 1e-12)
        assertArrayEquals(DoubleArray(reference.size) { exp(-4.0) * reference[it] }, model.predictScores(changed), 1e-12)
        val tied = model.predict(withMissing)
        assertEquals(Conditions.ids.first(), tied.label)
        assertEquals(0.0, tied.confidence, 0.0)
    }

    @Test
    fun rejectsInvalidVectorsIncludingNoUsableRetainedFeature() {
        val model = load(syntheticModel())
        for (size in listOf(0, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) { model.predict(DoubleArray(size)) }
        }
        for (value in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)) {
            val vector = DoubleArray(FeatureNames.count).apply { this[0] = value }
            assertThrows(IllegalArgumentException::class.java) { model.predictScores(vector) }
        }
        val missing = DoubleArray(FeatureNames.count) { Double.NaN }
        assertThrows(IllegalArgumentException::class.java) { model.predict(missing) }
        val onlyDropped = missing.copyOf().apply { this[lastIndex] = 1.0 }
        assertThrows(IllegalArgumentException::class.java) { model.predictScores(onlyDropped) }
    }

    @Test
    fun rejectsEmptyMalformedDuplicateAndTrailingJson() {
        val invalidJson = listOf(
            "",
            "{",
            "[]",
            "{schema:'incorrect'}",
            """{"schema":"a","schema":"b"}""",
            syntheticModel().toString() + "{}",
        )
        for (invalid in invalidJson) {
            assertThrows(IllegalArgumentException::class.java) { invalid.byteInputStream().use(FrozenMatlabModel::load) }
        }
    }

    @Test
    fun rejectsMissingOrInvalidModelFields() {
        val corruptions: List<JsonObject.() -> Unit> = listOf(
            { remove("alpha") },
            { addProperty("schema", "phonelab.vibro.matlab/2") },
            { addProperty("model_id", "another-model") },
            { addProperty("training_session", "another-session") },
            { getAsJsonArray("feature_names").set(0, JsonPrimitive(FeatureNames.numeric[1])) },
            { getAsJsonArray("conditions").set(0, JsonPrimitive(Conditions.ids[1])) },
            { getAsJsonArray("keep").set(0, JsonPrimitive(1)) },
            { getAsJsonArray("keep").set(0, JsonPrimitive(false)) },
            { getAsJsonArray("fill").remove(0) },
            { getAsJsonArray("mean").set(0, JsonPrimitive("0.0")) },
            { getAsJsonArray("std").set(0, JsonPrimitive(0)) },
            { addProperty("gamma", -1.0) },
            { addProperty("lambda", 0.0) },
            { getAsJsonArray("train_z").remove(74) },
            { getAsJsonArray("train_z")[0].asJsonArray.remove(0) },
            { getAsJsonArray("alpha")[0].asJsonArray.remove(0) },
            { getAsJsonArray("alpha")[0].asJsonArray.set(0, JsonParser.parseString("1e999")) },
        )
        for ((index, change) in corruptions.withIndex()) {
            assertThrows("Corruption $index", IllegalArgumentException::class.java) { load(syntheticModel().apply(change)) }
        }
    }

    private fun assetFile(): File = listOf(
        "src/main/assets/${FrozenMatlabModel.ASSET_FILENAME}",
        "apps/vibrodiag/src/main/assets/${FrozenMatlabModel.ASSET_FILENAME}",
    ).map(::File).firstOrNull(File::isFile) ?: error("Missing production frozen MATLAB model asset")

    private fun load(json: JsonObject): FrozenMatlabModel = json.toString().byteInputStream().use(FrozenMatlabModel::load)

    private fun syntheticModel(): JsonObject = JsonObject().apply {
        addProperty("schema", "phonelab.vibro.matlab/1")
        addProperty("model_id", FrozenMatlabModel.MODEL_ID)
        addProperty("training_session", FrozenMatlabModel.TRAINING_SESSION)
        add("feature_names", JsonArray().apply { FeatureNames.numeric.forEach { add(it) } })
        add("conditions", JsonArray().apply { Conditions.ids.forEach { add(it) } })
        add("keep", JsonArray().apply { repeat(32) { add(it < 31) } })
        add("fill", numbers(31) { if (it == 1) 7.0 else 0.0 })
        add("mean", numbers(31) { if (it == 1) 3.0 else 0.0 })
        add("std", numbers(31) { if (it == 1) 2.0 else 1.0 })
        addProperty("gamma", 1.0)
        addProperty("lambda", 0.1)
        add("train_z", JsonArray().apply {
            repeat(75) { add(numbers(31) { j -> if (j < 2) j + 1.0 else 0.0 }) }
        })
        val reference = doubleArrayOf(1.25, 1.25, -0.5, 0.0, 0.25)
        add("alpha", JsonArray().apply {
            repeat(75) { i -> add(numbers(5) { c -> if (i == 0) reference[c] else 0.0 }) }
        })
    }

    private fun numbers(size: Int, value: (Int) -> Double): JsonArray = JsonArray().apply {
        repeat(size) { add(value(it)) }
    }

    private companion object {
        const val SCORE_TOLERANCE = 2e-5
    }
}
