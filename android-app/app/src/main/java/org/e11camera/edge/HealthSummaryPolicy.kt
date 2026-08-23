package org.e11camera.edge

data class DeviceHealthSample(
    val sampledAt: Long,
    val cameraExpected: Boolean,
    val cameraOn: Boolean,
    val frameFresh: Boolean,
    val streamReady: Boolean,
    val encodedFps: Float,
    val encodedFpsValid: Boolean,
    val npuExpected: Boolean,
    val npuLoaded: Boolean,
    val npuFresh: Boolean,
    val npuInferenceMs: Long,
    val audioExpected: Boolean,
    val audioLoaded: Boolean,
    val audioFresh: Boolean,
    val freeStorageBytes: Long,
    val clipStorageBytes: Long
)

data class DeviceHealthSummary(
    val sampleCount: Int,
    val firstSampleAt: Long,
    val lastSampleAt: Long,
    val cameraAvailabilityPercent: Float,
    val streamAvailabilityPercent: Float,
    val cameraFreezeEpisodes: Int,
    val averageEncodedFps: Float,
    val minimumEncodedFps: Float,
    val npuAvailabilityPercent: Float,
    val averageNpuInferenceMs: Float,
    val maximumNpuInferenceMs: Long,
    val audioAvailabilityPercent: Float,
    val serviceStartCount: Int,
    val additionalRestartCount: Int,
    val freeStorageBytes: Long,
    val minimumFreeStorageBytes: Long,
    val freeStorageDeltaBytes: Long,
    val clipStorageBytes: Long,
    val clipStorageDeltaBytes: Long,
    val currentIssues: List<String>,
    val thermalStatus: String = "unavailable"
)

internal object HealthSummaryPolicy {
    const val WINDOW_MS = 24L * 60L * 60L * 1000L

    fun summarize(
        rawSamples: List<DeviceHealthSample>,
        rawServiceStarts: List<Long>,
        now: Long
    ): DeviceHealthSummary {
        val cutoff = now - WINDOW_MS
        val samples = rawSamples.filter { it.sampledAt in cutoff..now }.sortedBy { it.sampledAt }
        val starts = rawServiceStarts.count { it in cutoff..now }
        val cameraExpected = samples.filter { it.cameraExpected }
        val fpsSamples = cameraExpected.filter { it.encodedFpsValid }.map { it.encodedFps }
        val npuExpected = samples.filter { it.npuExpected }
        val npuCosts = npuExpected.map { it.npuInferenceMs }.filter { it > 0L }
        val audioExpected = samples.filter { it.audioExpected }
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val issues = mutableListOf<String>()
        last?.let { current ->
            if (current.cameraExpected && (!current.cameraOn || !current.frameFresh)) {
                issues += "摄像头画面未持续更新"
            } else if (current.cameraExpected && !current.streamReady) {
                issues += "编码推流未就绪"
            }
            if (current.npuExpected && !current.npuLoaded) {
                issues += "NPU 模型未加载"
            } else if (current.npuExpected && !current.npuFresh) {
                issues += "NPU 分析超过 7 秒未更新"
            }
            if (current.audioExpected && !current.audioLoaded) {
                issues += "哭声模型未加载"
            } else if (current.audioExpected && !current.audioFresh) {
                issues += "音频分析超过 5 秒未更新"
            }
        }
        return DeviceHealthSummary(
            sampleCount = samples.size,
            firstSampleAt = first?.sampledAt ?: 0L,
            lastSampleAt = last?.sampledAt ?: 0L,
            cameraAvailabilityPercent = percent(cameraExpected) { it.cameraOn && it.frameFresh },
            streamAvailabilityPercent = percent(cameraExpected) { it.streamReady },
            cameraFreezeEpisodes = countFreezeEpisodes(cameraExpected),
            averageEncodedFps = fpsSamples.averageOrUnavailable(),
            minimumEncodedFps = fpsSamples.minOrNull() ?: -1f,
            npuAvailabilityPercent = percent(npuExpected) { it.npuLoaded && it.npuFresh },
            averageNpuInferenceMs = npuCosts.map { it.toFloat() }.averageOrUnavailable(),
            maximumNpuInferenceMs = npuCosts.maxOrNull() ?: 0L,
            audioAvailabilityPercent = percent(audioExpected) { it.audioLoaded && it.audioFresh },
            serviceStartCount = starts,
            additionalRestartCount = (starts - 1).coerceAtLeast(0),
            freeStorageBytes = last?.freeStorageBytes ?: 0L,
            minimumFreeStorageBytes = samples.minOfOrNull { it.freeStorageBytes } ?: 0L,
            freeStorageDeltaBytes = if (first == null || last == null) 0L
                else last.freeStorageBytes - first.freeStorageBytes,
            clipStorageBytes = last?.clipStorageBytes ?: 0L,
            clipStorageDeltaBytes = if (first == null || last == null) 0L
                else last.clipStorageBytes - first.clipStorageBytes,
            currentIssues = issues
        )
    }

    private fun <T> percent(values: List<T>, healthy: (T) -> Boolean): Float {
        if (values.isEmpty()) return -1f
        return values.count(healthy) * 100f / values.size
    }

    private fun countFreezeEpisodes(samples: List<DeviceHealthSample>): Int {
        var episodes = 0
        var frozen = false
        for (sample in samples) {
            val currentFrozen = !sample.cameraOn || !sample.frameFresh
            if (currentFrozen && !frozen) episodes++
            frozen = currentFrozen
        }
        return episodes
    }

    private fun List<Float>.averageOrUnavailable(): Float =
        if (isEmpty()) -1f else average().toFloat()
}
