package com.mobilelab.vibrodiag

import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.model.TrainingSequence
import com.mobilelab.vibrodiag.model.TrainingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingSequenceTest {
    private val low = Conditions.ids[0]
    private val high = Conditions.ids[1]
    private val full = (1..Conditions.TARGET_GROUPS).toSet()

    @Test fun firstIncompleteConditionContinuesEvenWhenOtherConditionsHaveFewerGroups() {
        val recorded = mapOf(low to setOf(1), high to emptySet())
        assertEquals(TrainingTarget(low, 2), TrainingSequence.recommendation(null, recorded))
        assertEquals(TrainingTarget(low, 2), TrainingSequence.recommendation(low, recorded))
    }

    @Test fun explicitConditionKeepsItsOwnSequenceUntilItIsComplete() {
        for (group in 1..15) {
            val recorded = mapOf(high to (1 until group).toSet())
            assertEquals(TrainingTarget(high, group), TrainingSequence.recommendation(high, recorded))
        }
        assertEquals(TrainingTarget(low, 1), TrainingSequence.recommendation(high, mapOf(high to full)))
    }

    @Test fun gapsAreFilledAndCompletedConditionDoesNotWrapToGroupOne() {
        assertEquals(2, TrainingSequence.nextGroup(setOf(1, 3, 4)))
        assertNull(TrainingSequence.nextGroup(full))
        assertNull(TrainingSequence.recommendation(low, Conditions.ids.associateWith { full }))
    }

    @Test fun normalCaptureRejectsSkippedOrAlreadySavedGroupAndRedoRequiresExistingGroup() {
        val recorded = setOf(1, 3)
        assertTrue(TrainingSequence.canStart(TrainingTarget(low, 2), recorded, replace = false))
        assertFalse(TrainingSequence.canStart(TrainingTarget(low, 4), recorded, replace = false))
        assertFalse(TrainingSequence.canStart(TrainingTarget(low, 3), recorded, replace = false))
        assertTrue(TrainingSequence.canStart(TrainingTarget(low, 3), recorded, replace = true))
        assertFalse(TrainingSequence.canStart(TrainingTarget(low, 2), recorded, replace = true))
    }

    @Test fun interruptedGroupDoesNotAdvanceAndCanBeRetried() {
        val completed = setOf(1)
        val next = TrainingSequence.recommendation(low, mapOf(low to completed))
        assertEquals(TrainingTarget(low, 2), next)
        assertTrue(TrainingSequence.canStart(next!!, completed, replace = false))
        assertEquals(TrainingTarget(low, 3), TrainingSequence.recommendation(low, mapOf(low to (completed + 2))))
    }
}
