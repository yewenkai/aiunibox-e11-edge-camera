package org.e11camera.edge

/**
 * 把任意长度的 PCM16 分块组装成带重叠的浮点音频窗口。
 *
 * 这部分不依赖 Android，便于用 JVM 单元测试确认分块边界不会丢样。
 */
class PcmWindowAssembler(
    private val windowSamples: Int,
    private val hopSamples: Int
) {
    init {
        require(windowSamples > 0) { "windowSamples must be positive" }
        require(hopSamples in 1..windowSamples) { "hopSamples must be within the window" }
    }

    private val buffer = ShortArray(windowSamples)
    private var filled = 0

    fun push(samples: ShortArray, onWindow: (FloatArray) -> Unit) {
        var sourceOffset = 0
        while (sourceOffset < samples.size) {
            val copied = minOf(windowSamples - filled, samples.size - sourceOffset)
            System.arraycopy(samples, sourceOffset, buffer, filled, copied)
            filled += copied
            sourceOffset += copied

            if (filled == windowSamples) {
                val window = FloatArray(windowSamples)
                for (index in buffer.indices) {
                    window[index] = buffer[index] / 32768f
                }
                onWindow(window)

                val retained = windowSamples - hopSamples
                if (retained > 0) {
                    System.arraycopy(buffer, hopSamples, buffer, 0, retained)
                }
                filled = retained
            }
        }
    }

    fun reset() {
        filled = 0
    }
}
