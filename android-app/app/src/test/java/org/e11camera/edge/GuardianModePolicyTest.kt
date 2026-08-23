package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianModePolicyTest {
    @Test
    fun `normal guardian enables visual audio and clips`() {
        val mode = GuardianModePolicy.resolve(
            guardianEnabled = true,
            privacyMode = false,
            lensCoverClosed = false
        )

        assertEquals(GuardianOperatingMode.NORMAL, mode)
        assertTrue(GuardianModePolicy.visualEnabled(mode))
        assertTrue(GuardianModePolicy.audioEnabled(mode))
        assertTrue(GuardianModePolicy.clipEnabled(mode))
    }

    @Test
    fun `closed lens cover keeps only audio guardian`() {
        val mode = GuardianModePolicy.resolve(
            guardianEnabled = true,
            privacyMode = false,
            lensCoverClosed = true
        )

        assertEquals(GuardianOperatingMode.AUDIO_ONLY, mode)
        assertFalse(GuardianModePolicy.visualEnabled(mode))
        assertTrue(GuardianModePolicy.audioEnabled(mode))
        assertFalse(GuardianModePolicy.clipEnabled(mode))
    }

    @Test
    fun `software privacy pauses both sensing paths`() {
        val mode = GuardianModePolicy.resolve(
            guardianEnabled = true,
            privacyMode = true,
            lensCoverClosed = true
        )

        assertEquals(GuardianOperatingMode.PRIVACY_PAUSED, mode)
        assertFalse(GuardianModePolicy.visualEnabled(mode))
        assertFalse(GuardianModePolicy.audioEnabled(mode))
    }

    @Test
    fun `disabled guardian remains off regardless of lens cover`() {
        assertEquals(
            GuardianOperatingMode.OFF,
            GuardianModePolicy.resolve(
                guardianEnabled = false,
                privacyMode = false,
                lensCoverClosed = true
            )
        )
    }
}
