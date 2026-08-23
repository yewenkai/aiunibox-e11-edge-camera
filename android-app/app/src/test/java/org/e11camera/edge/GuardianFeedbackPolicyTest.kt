package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianFeedbackPolicyTest {
    @Test
    fun `feedback accepts only explicit local labels`() {
        assertEquals("false_alarm", GuardianFeedbackPolicy.normalize(" FALSE_ALARM "))
        assertNull(GuardianFeedbackPolicy.normalize("auto_train"))
        assertNull(GuardianFeedbackPolicy.normalize(""))
    }
}
