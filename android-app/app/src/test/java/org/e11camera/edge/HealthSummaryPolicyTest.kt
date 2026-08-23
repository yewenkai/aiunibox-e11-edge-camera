package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthSummaryPolicyTest {
    @Test
    fun `intentional camera pause is excluded and freezes are counted by episode`() {
        val samples = listOf(
            sample(1_000, expected = true, fresh = true),
            sample(2_000, expected = true, fresh = false),
            sample(3_000, expected = true, fresh = false),
            sample(4_000, expected = true, fresh = true),
            sample(5_000, expected = false, fresh = false)
        )
        val summary = HealthSummaryPolicy.summarize(samples, listOf(1_000), 5_000)
        assertEquals(50f, summary.cameraAvailabilityPercent, 0.001f)
        assertEquals(1, summary.cameraFreezeEpisodes)
        assertFalse(summary.currentIssues.any { it.contains("摄像头") })
    }

    @Test
    fun `service starts and storage changes remain explainable`() {
        val first = sample(1_000, free = 1_000, clips = 100)
        val last = sample(2_000, free = 700, clips = 250)
        val summary = HealthSummaryPolicy.summarize(
            listOf(first, last), listOf(500, 1_500, 1_800), 2_000
        )
        assertEquals(3, summary.serviceStartCount)
        assertEquals(2, summary.additionalRestartCount)
        assertEquals(-300, summary.freeStorageDeltaBytes)
        assertEquals(150, summary.clipStorageDeltaBytes)
    }

    private fun sample(
        at: Long,
        expected: Boolean = true,
        fresh: Boolean = true,
        free: Long = 1_000,
        clips: Long = 100
    ) = DeviceHealthSample(
        sampledAt = at,
        cameraExpected = expected,
        cameraOn = fresh,
        frameFresh = fresh,
        streamReady = fresh,
        encodedFps = 30f,
        encodedFpsValid = expected,
        npuExpected = false,
        npuLoaded = false,
        npuFresh = true,
        npuInferenceMs = 0L,
        audioExpected = false,
        audioLoaded = false,
        audioFresh = true,
        freeStorageBytes = free,
        clipStorageBytes = clips
    )
}
