package org.e11camera.edge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerStateMachineTest {
    @Test
    fun `default sustained path requires eight active samples in fifteen`() {
        val machine = TriggerStateMachine()
        repeat(7) { assertFalse(machine.push(true)) }
        repeat(7) { assertFalse(machine.push(false)) }

        assertTrue(machine.push(true))
        assertTrue(machine.isActive)
    }

    @Test
    fun `fast path triggers on two strong samples in three`() {
        val machine = TriggerStateMachine(triggerWindow = 3, triggerMinActive = 2, quietReset = 6)

        assertFalse(machine.push(true))
        assertFalse(machine.push(false))
        assertTrue(machine.push(true))
        assertTrue(machine.isActive)
    }

    @Test
    fun `active state resets only after configured quiet run`() {
        val machine = TriggerStateMachine(triggerWindow = 3, triggerMinActive = 2, quietReset = 4)
        machine.push(true)
        machine.push(false)
        machine.push(true)
        repeat(3) { machine.push(false) }
        assertTrue(machine.isActive)

        machine.push(false)
        assertFalse(machine.isActive)
    }
}
