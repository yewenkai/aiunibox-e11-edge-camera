package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionStateMachineTest {
    @Test
    fun `two people require stable confirmation before suppressing notifications`() {
        val machine = SupervisionStateMachine()
        val two = observation(total = 2)

        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(two, 1_000))
        assertFalse(machine.notificationsSuppressed)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(two, 3_000))
        assertEquals(SupervisionStateMachine.State.CAREGIVER_PRESENT, machine.push(two, 5_000))
        assertTrue(machine.notificationsSuppressed)
    }

    @Test
    fun `single unknown person never counts as caregiver because of location`() {
        val machine = SupervisionStateMachine()
        val one = observation(total = 1)

        machine.push(one, 1_000)
        machine.push(one, 3_000)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(one, 5_000))
        assertFalse(machine.notificationsSuppressed)
    }

    @Test
    fun `caregiver absence rearms unknown when baby was never confirmed`() {
        val machine = SupervisionStateMachine(caregiverLeaveMs = 20_000)
        val two = observation(total = 2)
        val none = observation(total = 0)
        machine.push(two, 1_000)
        machine.push(two, 3_000)
        machine.push(two, 5_000)

        assertEquals(SupervisionStateMachine.State.CAREGIVER_PRESENT, machine.push(none, 15_000))
        machine.push(none, 23_001)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(none, 25_001))
        assertFalse(machine.notificationsSuppressed)
    }

    @Test
    fun `single person does not claim baby identity`() {
        val machine = SupervisionStateMachine()
        val one = observation(total = 1)

        machine.push(one, 1_000)
        machine.push(one, 3_000)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(one, 5_000))
        assertFalse(machine.notificationsSuppressed)
    }

    @Test
    fun `caregiver state returns to unknown after hold expires`() {
        val machine = SupervisionStateMachine(caregiverLeaveMs = 20_000)
        val caregiver = observation(total = 2)
        val none = observation(total = 0)
        machine.push(caregiver, 7_000)
        machine.push(caregiver, 9_000)
        machine.push(caregiver, 11_000)

        machine.push(none, 20_000)
        machine.push(none, 31_001)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, machine.push(none, 33_001))
        assertFalse(machine.notificationsSuppressed)
    }

    @Test
    fun `single adult sized person counts as caregiver`() {
        val machine = SupervisionStateMachine()
        val adult = observation(total = 1, adultLike = 1)

        assertTrue(adult.caregiverEvidence)
        machine.push(adult, 1_000)
        machine.push(adult, 3_000)
        assertEquals(SupervisionStateMachine.State.CAREGIVER_PRESENT, machine.push(adult, 5_000))
        assertTrue(machine.notificationsSuppressed)
    }

    private fun observation(
        total: Int,
        adultLike: Int = 0
    ) = SupervisionStateMachine.Observation(total, adultLike)
}
