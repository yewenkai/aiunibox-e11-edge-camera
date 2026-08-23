package org.e11camera.edge

internal data class AdultBodyThresholds(
    val minHeight: Float,
    val minArea: Float
)

/** 场景校准的纯数值逻辑，不接触音频、画面或 Android 存储。 */
internal object SceneCalibrationPolicy {
    fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = ((sorted.lastIndex) * percentile.coerceIn(0f, 1f)).toInt()
        return sorted[index]
    }

    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    fun adultThresholds(medianHeight: Float, medianArea: Float): AdultBodyThresholds =
        AdultBodyThresholds(
            minHeight = (medianHeight * 0.72f).coerceIn(0.30f, 0.65f),
            minArea = (medianArea * 0.62f).coerceIn(0.06f, 0.25f)
        )
}
