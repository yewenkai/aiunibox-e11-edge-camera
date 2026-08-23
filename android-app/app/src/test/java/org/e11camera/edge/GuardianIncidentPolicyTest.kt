package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianIncidentPolicyTest {
    @Test
    fun `signals at one minute boundary remain in the same incident`() {
        assertTrue(GuardianIncidentPolicy.canMerge(10_000L, 70_000L))
        assertFalse(GuardianIncidentPolicy.canMerge(10_000L, 70_001L))
    }

    @Test
    fun `clock rollback never merges incidents`() {
        assertFalse(GuardianIncidentPolicy.canMerge(10_000L, 9_999L))
    }

    @Test
    fun `mixed signal summary preserves both counts`() {
        assertEquals("宝宝守护：动作与疑似哭声", GuardianIncidentPolicy.title(3, 2))
        assertEquals("1 分钟事件会话：画面动作 ×3 · 疑似哭声 ×2", GuardianIncidentPolicy.summary(3, 2))
    }
}
