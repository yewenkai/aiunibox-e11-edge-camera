package org.e11camera.edge

internal object GuardianFeedbackPolicy {
    const val NEEDS_ATTENTION = "attention"
    const val FALSE_ALARM = "false_alarm"
    const val CAREGIVER_PRESENT = "caregiver_present"
    const val CORRECT = "correct"
    const val INCORRECT = "incorrect"

    val values = setOf(NEEDS_ATTENTION, FALSE_ALARM, CAREGIVER_PRESENT, CORRECT, INCORRECT)

    fun normalize(value: String): String? = value.trim().lowercase().takeIf { it in values }
}
