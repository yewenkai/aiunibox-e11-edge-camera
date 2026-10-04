package org.e11camera.edge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampOverlayRegionTest {
    @Test
    fun defaultOverlayKeepsOriginalPixelPositionWithAntialiasMargin() {
        val region = TimestampOverlayRegion.around(640, 480, 12f, 12f, 258f, 44f)
        assertEquals(TimestampOverlayRegion(11, 11, 248, 34), region)
        val quad = region.quad(640, 480)
        // 转回视频像素，底部/顶部顶点不能颠倒，也不能因裁纹理移动文字。
        assertArrayEquals(
            floatArrayOf(11f, 45f, 259f, 45f, 11f, 11f, 259f, 11f),
            quad.mapIndexed { index, value ->
                if (index % 2 == 0) (value + 1f) * 320f else (1f - value) * 240f
            }.toFloatArray(),
            0.0001f
        )
    }

    @Test
    fun fractionalLayoutRetainsFullAntialiasedBoundary() {
        assertEquals(
            TimestampOverlayRegion(10, 10, 248, 35),
            TimestampOverlayRegion.around(641, 481, 11.2f, 11.8f, 256.1f, 43.5f)
        )
    }

    @Test
    fun croppedOverlayCannotExtendPastVeryShortVideo() {
        val region = TimestampOverlayRegion.around(640, 20, 12f, 12f, 258f, 44f)
        assertEquals(TimestampOverlayRegion(11, 11, 248, 9), region)
        assertEquals(-1f, region.quad(640, 20)[1], 0f)
    }

    @Test
    fun overlayTouchingFrameEdgeDoesNotAddTransparentPixelsOutsideFrame() {
        val region = TimestampOverlayRegion.around(100, 40, 0f, 0f, 100f, 40f)
        assertEquals(TimestampOverlayRegion(0, 0, 100, 40), region)
        assertArrayEquals(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            region.quad(100, 40), 0f
        )
    }
}
