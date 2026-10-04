package org.e11camera.edge

import kotlin.math.ceil
import kotlin.math.floor

/** 时间戳背景外保留一个抗锯齿像素，像素坐标仍以原始视频左上角为原点。 */
internal data class TimestampOverlayRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    fun quad(frameWidth: Int, frameHeight: Int): FloatArray {
        val x0 = 2f * left / frameWidth - 1f
        val x1 = 2f * (left + width) / frameWidth - 1f
        val y0 = 1f - 2f * top / frameHeight
        val y1 = 1f - 2f * (top + height) / frameHeight
        // 与 FULL_QUAD / BITMAP_UV 一致：左下、右下、左上、右上。
        return floatArrayOf(x0, y1, x1, y1, x0, y0, x1, y0)
    }

    companion object {
        fun around(
            frameWidth: Int,
            frameHeight: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ): TimestampOverlayRegion {
            require(frameWidth > 0 && frameHeight > 0)
            require(right > left && bottom > top)
            val x0 = (floor(left).toInt() - 1).coerceIn(0, frameWidth - 1)
            val y0 = (floor(top).toInt() - 1).coerceIn(0, frameHeight - 1)
            val x1 = (ceil(right).toInt() + 1).coerceIn(x0 + 1, frameWidth)
            val y1 = (ceil(bottom).toInt() + 1).coerceIn(y0 + 1, frameHeight)
            return TimestampOverlayRegion(x0, y0, x1 - x0, y1 - y0)
        }
    }
}
