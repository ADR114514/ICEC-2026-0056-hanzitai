package com.mobilelab.vibrodiag.model

internal data class TrainingTarget(val conditionId: String, val group: Int)

/** Choose numbered training groups by occupancy, not by the total samples in each condition. */
internal object TrainingSequence {
    fun nextGroup(completed: Set<Int>): Int? =
        (1..Conditions.TARGET_GROUPS).firstOrNull { it !in completed }

    fun recommendation(preferredCondition: String?, completed: Map<String, Set<Int>>): TrainingTarget? {
        val inOrder = listOfNotNull(preferredCondition?.takeIf { it in Conditions.ids }) + Conditions.ids
        return inOrder.firstNotNullOfOrNull { id ->
            nextGroup(completed[id].orEmpty())?.let { TrainingTarget(id, it) }
        }
    }

    fun canStart(target: TrainingTarget, completed: Set<Int>, replace: Boolean): Boolean =
        target.conditionId in Conditions.ids && target.group in 1..Conditions.TARGET_GROUPS &&
            if (replace) target.group in completed else target.group == nextGroup(completed)
}
