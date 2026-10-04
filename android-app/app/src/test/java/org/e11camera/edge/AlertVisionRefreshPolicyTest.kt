package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVisionRefreshPolicyTest {
    @Test
    fun `queue time is deducted before allocating capture time`() {
        val deadline = 1_600L
        assertEquals(600L, AlertVisionRefreshPolicy.remainingMs(deadline, 1_000L))
        assertEquals(350L, AlertVisionRefreshPolicy.remainingMs(deadline, 1_250L))
        assertEquals(250L, AlertVisionRefreshPolicy.captureWaitMs(350L, 60L))
        assertEquals(0L, AlertVisionRefreshPolicy.remainingMs(deadline, 1_601L))
    }

    @Test
    fun `fresh capture has room for measured inference and never consumes total deadline`() {
        assertEquals(450L, AlertVisionRefreshPolicy.captureWaitMs(600L, 60L))
        assertEquals(300L, AlertVisionRefreshPolicy.captureWaitMs(600L, 400L))
        assertEquals(0L, AlertVisionRefreshPolicy.captureWaitMs(89L, 10L))
        assertEquals(450L, AlertVisionRefreshPolicy.captureWaitMs(600L, 0L))
    }

    @Test
    fun `source age uses monotonic time through wall clock adjustment`() {
        assertEquals(300L, AlertVisionRefreshPolicy.sourceAgeMs(1_000L, 5_000L, 9_300L, 9_000L))
        assertEquals(2_000L, AlertVisionRefreshPolicy.sourceAgeMs(100_000L, 5_000L, 11_000L, 9_000L))
        assertFalse(AlertVisionRefreshPolicy.sourceUsable(2_000L))
    }

    @Test
    fun `unknown and future timestamps never authorize adult suppression`() {
        assertEquals(-1L, AlertVisionRefreshPolicy.sourceAgeMs(10_000L, 0L, 50_000L, 49_000L))
        assertEquals(-1L, AlertVisionRefreshPolicy.sourceAgeMs(10_000L, 10_001L, 50_000L, 0L))
        assertEquals(-1L, AlertVisionRefreshPolicy.sourceAgeMs(10_000L, 9_000L, 50_000L, 50_001L))
        assertFalse(AlertVisionRefreshPolicy.sourceUsable(-1L))
    }

    @Test
    fun `cached evidence has explicit inclusive limits without relabeling capture time`() {
        assertTrue(AlertVisionRefreshPolicy.sourceUsable(1_500L))
        assertFalse(AlertVisionRefreshPolicy.sourceUsable(1_501L))
        assertTrue(AlertVisionRefreshPolicy.sourceUsable(500L, AlertVisionRefreshPolicy.RECENT_SOURCE_REUSE_MS))
        assertFalse(AlertVisionRefreshPolicy.sourceUsable(501L, AlertVisionRefreshPolicy.RECENT_SOURCE_REUSE_MS))
    }

    private fun caregiverSnapshot() = AlertVisionSnapshot(
        "refreshed", 10_250L, 10_000L, 65L, 2, 1,
        SupervisionStateMachine.State.CAREGIVER_PRESENT, true
    )

    @Test
    fun `expired adult evidence cannot suppress while source timestamps remain honest`() {
        val decision = AlertVisionRefreshPolicy.safeDecision(caregiverSnapshot(), 1_501L, true)
        assertFalse(decision.notificationsSuppressed)
        assertEquals(0, decision.personCount)
        assertEquals(0, decision.adultLikePersonCount)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, decision.supervisionState)
        assertEquals(10_000L, decision.sourceCapturedAt)
        assertEquals(10_250L, decision.analyzedAt)
        assertEquals(65L, decision.inferenceMs)
        assertEquals("source_expired", decision.degradedReason)
    }

    @Test
    fun `stopped detector and unknown source age cannot authorize suppression`() {
        assertFalse(AlertVisionRefreshPolicy.safeDecision(caregiverSnapshot(), 100L, false).notificationsSuppressed)
        assertEquals("detector_stopped", AlertVisionRefreshPolicy.safeDecision(caregiverSnapshot(), 100L, false).degradedReason)
        assertFalse(AlertVisionRefreshPolicy.safeDecision(caregiverSnapshot(), -1L, true).notificationsSuppressed)
    }

    @Test
    fun `timeout fallback has stricter evidence age than completed fresh inference`() {
        val snapshot = caregiverSnapshot()
        assertTrue(AlertVisionRefreshPolicy.safeDecision(snapshot, 501L, true).notificationsSuppressed)
        assertFalse(AlertVisionRefreshPolicy.safeDecision(snapshot, 501L, true, 500L).notificationsSuppressed)
        assertTrue(AlertVisionRefreshPolicy.safeDecision(snapshot, 500L, true, 500L).notificationsSuppressed)
    }

    @Test
    fun `latest frame routing uses five hundred milliseconds while fresh routing allows fifteen hundred`() {
        val fresh = caregiverSnapshot()
        val cached = fresh.copy(refreshStatus = "latest_frame")
        val freshLimit = AlertVisionRefreshPolicy.maxAgeForAlert(fresh.refreshStatus)
        val cachedLimit = AlertVisionRefreshPolicy.maxAgeForAlert(cached.refreshStatus)
        assertEquals(1_500L, freshLimit)
        assertEquals(500L, cachedLimit)
        assertTrue(AlertVisionRefreshPolicy.safeDecision(cached, 500L, true, cachedLimit).notificationsSuppressed)
        assertFalse(AlertVisionRefreshPolicy.safeDecision(cached, 501L, true, cachedLimit).notificationsSuppressed)
        assertTrue(AlertVisionRefreshPolicy.safeDecision(fresh, 501L, true, freshLimit).notificationsSuppressed)
        assertTrue(AlertVisionRefreshPolicy.safeDecision(fresh, 1_500L, true, freshLimit).notificationsSuppressed)
        assertFalse(AlertVisionRefreshPolicy.safeDecision(fresh, 1_501L, true, freshLimit).notificationsSuppressed)
    }

    @Test
    fun `cached source that expires during inference cannot publish adult evidence`() {
        val limit = AlertVisionRefreshPolicy.maxAgeForAlert("latest_frame")
        assertTrue(AlertVisionRefreshPolicy.sourceUsable(430L, limit))
        assertFalse(AlertVisionRefreshPolicy.sourceUsable(501L, limit))
        val cached = caregiverSnapshot().copy(refreshStatus = "latest_frame", sourceMaxAgeMs = limit)
        assertFalse(AlertVisionRefreshPolicy.safeDecision(cached, 501L, true, limit).notificationsSuppressed)
    }

    @Test
    fun `renaming cached analysis as current decision cannot widen its original source limit`() {
        val current = caregiverSnapshot().copy(refreshStatus = "current_state", sourceMaxAgeMs = 500L)
        val currentLimit = AlertVisionRefreshPolicy.maxAgeForAlert("current_state")
        assertTrue(AlertVisionRefreshPolicy.safeDecision(current, 500L, true, currentLimit).notificationsSuppressed)
        val expired = AlertVisionRefreshPolicy.safeDecision(current, 501L, true, currentLimit)
        assertFalse(expired.notificationsSuppressed)
        assertEquals(0, expired.personCount)
        assertEquals(SupervisionStateMachine.State.UNKNOWN, expired.supervisionState)
        assertEquals(10_000L, expired.sourceCapturedAt)
        assertEquals(500L, expired.sourceMaxAgeMs)
    }
}
