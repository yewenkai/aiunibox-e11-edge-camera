package org.e11camera.edge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerMarkerPolicyTest {
    @Test
    fun `fresh heartbeat reports viewer and explicit close reports none`() {
        assertTrue(ViewerMarkerPolicy.isWatching("1 1000\n", 12_000))
        assertFalse(ViewerMarkerPolicy.isWatching("0 12000\n", 12_000))
    }

    @Test
    fun `expired true is revoked without an unread event`() {
        assertTrue(ViewerMarkerPolicy.isWatching("1 1000", 36_000))
        assertFalse(ViewerMarkerPolicy.isWatching("1 1000", 36_001))
    }

    @Test
    fun `previous boot timestamp cannot keep camera awake`() {
        assertFalse(ViewerMarkerPolicy.isWatching("1 10000000", 1000))
        assertFalse(ViewerMarkerPolicy.isWatching("1 -1", 1000))
    }

    @Test
    fun `missing legacy and malformed markers never retain true`() {
        for (value in listOf("", "1", "2 1000", "1 NaN", "1 1000 extra", "1 9223372036854775808")) {
            assertFalse(value, ViewerMarkerPolicy.isWatching(value, 1000))
        }
    }

    @Test
    fun `invalid clocks and limits fail closed`() {
        assertFalse(ViewerMarkerPolicy.isWatching("1 0", -1))
        assertFalse(ViewerMarkerPolicy.isWatching("1 0", 1, -1))
        assertTrue(ViewerMarkerPolicy.isWatching("1 1", 1, 0))
    }
}
