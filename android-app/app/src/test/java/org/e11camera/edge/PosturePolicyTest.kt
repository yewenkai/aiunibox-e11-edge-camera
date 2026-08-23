package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosturePolicyTest {
    @Test
    fun `visible face and shoulders is supine candidate`() {
        val points = points(
            0 to Triple(0.50f, 0.20f, 0.9f),
            1 to Triple(0.47f, 0.18f, 0.9f),
            2 to Triple(0.53f, 0.18f, 0.9f),
            3 to Triple(0.44f, 0.20f, 0.8f),
            4 to Triple(0.56f, 0.20f, 0.8f),
            5 to Triple(0.40f, 0.35f, 0.9f),
            6 to Triple(0.60f, 0.35f, 0.9f)
        )

        val result = PostureClassificationPolicy.classify(person(points))

        assertEquals(PostureLabel.SUPINE, result.label)
        assertTrue(result.faceKeypointValidity >= 0.8f)
    }

    @Test
    fun `body visible with missing face is prone candidate`() {
        val points = points(
            5 to Triple(0.40f, 0.35f, 0.9f),
            6 to Triple(0.60f, 0.35f, 0.9f),
            11 to Triple(0.43f, 0.65f, 0.9f),
            12 to Triple(0.57f, 0.65f, 0.9f)
        )

        val result = PostureClassificationPolicy.classify(person(points))

        assertEquals(PostureLabel.PRONE, result.label)
        assertEquals(0f, result.faceKeypointValidity)
    }

    @Test
    fun `single side face is side candidate`() {
        val points = points(
            1 to Triple(0.48f, 0.20f, 0.9f),
            3 to Triple(0.45f, 0.22f, 0.9f),
            5 to Triple(0.46f, 0.35f, 0.9f),
            6 to Triple(0.54f, 0.35f, 0.9f),
            11 to Triple(0.47f, 0.65f, 0.9f),
            12 to Triple(0.53f, 0.65f, 0.9f)
        )

        assertEquals(PostureLabel.SIDE, PostureClassificationPolicy.classify(person(points)).label)
    }

    @Test
    fun `stable posture requires five votes in six samples`() {
        val machine = PostureStateMachine()
        repeat(4) { assertNull(machine.push(PostureLabel.PRONE)) }
        val entered = machine.push(PostureLabel.PRONE)

        assertEquals(PostureLabel.UNKNOWN, entered?.previous)
        assertEquals(PostureLabel.PRONE, entered?.current)
        assertEquals(PostureLabel.PRONE, machine.state)
        assertNull(machine.push(PostureLabel.SUPINE))
    }

    @Test
    fun `side posture is risk only when configured`() {
        assertFalse(PostureClassificationPolicy.isRisk(PostureLabel.SIDE, false))
        assertTrue(PostureClassificationPolicy.isRisk(PostureLabel.SIDE, true))
        assertTrue(PostureClassificationPolicy.isRisk(PostureLabel.PRONE, false))
    }

    private fun person(keypoints: FloatArray) = PosePerson(
        box = floatArrayOf(0.2f, 0.1f, 0.6f, 0.8f, 0.9f),
        keypoints = keypoints
    )

    private fun points(vararg values: Pair<Int, Triple<Float, Float, Float>>): FloatArray {
        val result = FloatArray(PostureClassificationPolicy.KEYPOINT_COUNT * 3)
        values.forEach { (index, point) ->
            result[index * 3] = point.first
            result[index * 3 + 1] = point.second
            result[index * 3 + 2] = point.third
        }
        return result
    }
}
