package org.e11camera.edge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmWindowAssemblerTest {
    @Test
    fun `arbitrary chunks preserve samples and overlap`() {
        val assembler = PcmWindowAssembler(windowSamples = 4, hopSamples = 2)
        val windows = mutableListOf<FloatArray>()

        assembler.push(shortArrayOf(0, 1, 2), windows::add)
        assembler.push(shortArrayOf(3, 4, 5, 6, 7), windows::add)

        assertEquals(3, windows.size)
        assertPcm(shortArrayOf(0, 1, 2, 3), windows[0])
        assertPcm(shortArrayOf(2, 3, 4, 5), windows[1])
        assertPcm(shortArrayOf(4, 5, 6, 7), windows[2])
    }

    @Test
    fun `reset drops audio retained before speaker playback`() {
        val assembler = PcmWindowAssembler(windowSamples = 4, hopSamples = 2)
        val windows = mutableListOf<FloatArray>()

        assembler.push(shortArrayOf(100, 101, 102), windows::add)
        assembler.reset()
        assembler.push(shortArrayOf(1, 2, 3, 4), windows::add)

        assertEquals(1, windows.size)
        assertPcm(shortArrayOf(1, 2, 3, 4), windows.single())
    }

    private fun assertPcm(expected: ShortArray, actual: FloatArray) {
        val restored = actual.map { (it * 32768f).toInt() }.toIntArray()
        assertArrayEquals(expected.map(Short::toInt).toIntArray(), restored)
    }
}
