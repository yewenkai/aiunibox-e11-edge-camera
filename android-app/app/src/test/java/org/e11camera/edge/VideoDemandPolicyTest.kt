package org.e11camera.edge

import org.junit.Assert.*
import org.junit.Test

class VideoDemandPolicyTest {
    private val enabled = VideoDemandPolicy.Input(true, false, false, false)

    @Test fun `idle grace expires into standby without changing manual intent`() {
        val p = VideoDemandPolicy()
        p.touch(100)
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled, 30_099))
        assertEquals(30_100L, p.nextCheckAt(enabled, 101))
        assertEquals(VideoDemandPolicy.Mode.STANDBY, p.mode(enabled, 30_100))
        assertNull(p.nextCheckAt(enabled, 30_100))
    }
    @Test fun `request holds video until all consumers release then gets idle grace`() {
        val p = VideoDemandPolicy()
        val a = p.acquire(1); val b = p.acquire(2)
        p.release(a, 3)
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled, 50_000))
        assertNull(p.nextCheckAt(enabled, 50_000))
        p.release(b, 50_000)
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled, 79_999))
        assertEquals(VideoDemandPolicy.Mode.STANDBY, p.mode(enabled, 80_000))
    }
    @Test fun `manual pause and privacy override every type of demand`() {
        val p = VideoDemandPolicy(); p.acquire(0); p.renewExternal("media", true, 0)
        val all = enabled.copy(visualGuardian = true, watching = true, preview = true)
        assertEquals(VideoDemandPolicy.Mode.MANUAL_PAUSED, p.mode(all.copy(captureEnabled = false), 1))
        assertEquals(VideoDemandPolicy.Mode.PRIVACY_PAUSED, p.mode(all.copy(privacy = true), 1))
        assertNull(p.nextCheckAt(all.copy(privacy = true), 1))
    }
    @Test fun `visual guardian and preview prevent standby but audio only does not`() {
        val p = VideoDemandPolicy()
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled.copy(visualGuardian = true), 40_000))
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled.copy(preview = true), 40_000))
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled.copy(watching = true), 40_000))
        assertEquals(VideoDemandPolicy.Mode.STANDBY, p.mode(enabled, 40_000))
    }
    @Test fun `external wake renewals expire if the media hook dies`() {
        val p = VideoDemandPolicy(); p.renewExternal("media", true, 0)
        assertEquals(30_000L, p.nextCheckAt(enabled, 1))
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled, 30_000))
        assertEquals(35_000L, p.nextCheckAt(enabled, 30_000))
        p.renewExternal("media", true, 10_000)
        assertEquals(VideoDemandPolicy.Mode.ACTIVE, p.mode(enabled, 44_999))
        assertEquals(VideoDemandPolicy.Mode.STANDBY, p.mode(enabled, 45_000))
    }
    @Test fun `clear prevents old request releases extending a new runtime`() {
        val p = VideoDemandPolicy(); val old = p.acquire(0); p.clear(); p.release(old, 50_000)
        assertEquals(VideoDemandPolicy.Mode.STANDBY, p.mode(enabled, 50_000))
        val next = p.acquire(50_001); assertNotEquals(old, next)
        assertEquals(1, p.requestCount)
    }
}
