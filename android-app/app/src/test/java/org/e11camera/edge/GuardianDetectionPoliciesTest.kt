package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianDetectionPoliciesTest {
    @Test
    fun `strong cry triggers immediately`() {
        val policy = CryTemporalPolicy()

        assertTrue(policy.push(score = 0.18f, threshold = 0.18f))
    }

    @Test
    fun `two overlapping weak cry candidates trigger while isolated candidate does not`() {
        val policy = CryTemporalPolicy()

        assertFalse(policy.push(score = 0.13f, threshold = 0.18f))
        assertTrue(policy.push(score = 0.14f, threshold = 0.18f))

        policy.reset()
        assertFalse(policy.push(score = 0.14f, threshold = 0.18f))
        assertFalse(policy.push(score = 0.04f, threshold = 0.18f))
        assertFalse(policy.push(score = 0.03f, threshold = 0.18f))
    }

    @Test
    fun `perspective adjustment accepts far adult but rejects near small baby`() {
        assertTrue(
            AdultEvidencePolicy.isAdultLike(
                box = floatArrayOf(0.35f, 0.10f, 0.24f, 0.40f),
                minHeight = 0.48f,
                minArea = 0.14f
            )
        )
        assertFalse(
            AdultEvidencePolicy.isAdultLike(
                box = floatArrayOf(0.40f, 0.62f, 0.25f, 0.30f),
                minHeight = 0.48f,
                minArea = 0.14f
            )
        )
        assertFalse(AdultEvidencePolicy.isAdultLike(floatArrayOf(0f, 0f), 0.48f, 0.14f))
    }

    @Test
    fun `motion supports strong short event and slower accumulated change`() {
        val strong = MotionSignalPolicy.evaluate(shortRatio = 0.23, lagRatio = 0.0, baseThreshold = 0.07)
        assertTrue(strong.regularActive)
        assertTrue(strong.strongActive)

        val accumulated = MotionSignalPolicy.evaluate(shortRatio = 0.02, lagRatio = 0.09, baseThreshold = 0.07)
        assertTrue(accumulated.regularActive)
        assertFalse(accumulated.strongActive)
    }

    @Test
    fun `pose target follows previous person instead of jumping to smaller newcomer`() {
        val previous = floatArrayOf(0.10f, 0.15f, 0.30f, 0.60f)
        val tracked = person(0.12f, 0.16f, 0.30f, 0.60f)
        val smallerNewcomer = person(0.70f, 0.40f, 0.12f, 0.25f)

        val selected = PoseTargetPolicy.select(
            persons = listOf(smallerNewcomer, tracked),
            previousBox = previous,
            isAdultLike = { false }
        )

        assertSame(tracked, selected)
        assertTrue(PoseTargetPolicy.boxIou(previous, tracked.box) > 0.15f)
    }

    @Test
    fun `pose fallback prefers smaller non adult candidate`() {
        val adult = person(0.05f, 0.05f, 0.55f, 0.90f)
        val baby = person(0.60f, 0.45f, 0.25f, 0.35f)

        val selected = PoseTargetPolicy.select(
            persons = listOf(adult, baby),
            previousBox = FloatArray(0),
            isAdultLike = { it === adult }
        )

        assertSame(baby, selected)
        assertEquals(0.25f, selected?.box?.get(2))
    }

    @Test
    fun `adult entering previous target area cannot steal pose while baby remains visible`() {
        val previous = floatArrayOf(0.20f, 0.20f, 0.25f, 0.40f)
        val adult = person(0.18f, 0.10f, 0.45f, 0.85f)
        val baby = person(0.62f, 0.50f, 0.22f, 0.32f)

        val selected = PoseTargetPolicy.select(
            persons = listOf(adult, baby),
            previousBox = previous,
            isAdultLike = { it === adult }
        )

        assertSame(baby, selected)
    }

    private fun person(x: Float, y: Float, w: Float, h: Float) = PosePerson(
        box = floatArrayOf(x, y, w, h, 0.9f),
        keypoints = FloatArray(PostureClassificationPolicy.KEYPOINT_COUNT * 3)
    )
}
