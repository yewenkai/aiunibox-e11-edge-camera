package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneCalibrationPolicyTest {
    @Test
    fun `ambient percentile ignores one unusually loud final sample`() {
        val values = (1..14).map { 0.01f } + 0.90f
        assertEquals(0.01f, SceneCalibrationPolicy.percentile(values, 0.90f), 0.0001f)
    }

    @Test
    fun `adult threshold keeps seated and distance margin`() {
        val result = SceneCalibrationPolicy.adultThresholds(0.70f, 0.20f)
        assertEquals(0.504f, result.minHeight, 0.0001f)
        assertEquals(0.124f, result.minArea, 0.0001f)
    }

    @Test
    fun `adult threshold stays inside safe calibration bounds`() {
        val small = SceneCalibrationPolicy.adultThresholds(0.10f, 0.01f)
        val large = SceneCalibrationPolicy.adultThresholds(1.0f, 0.8f)
        assertEquals(0.30f, small.minHeight, 0.0001f)
        assertEquals(0.06f, small.minArea, 0.0001f)
        assertEquals(0.65f, large.minHeight, 0.0001f)
        assertEquals(0.25f, large.minArea, 0.0001f)
    }
}
