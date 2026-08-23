package org.e11camera.edge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class DeviceHealthInput(
    val cameraExpected: Boolean,
    val cameraOn: Boolean,
    val frameCapturedAt: Long,
    val streamReady: Boolean,
    val encodedFrameCount: Long,
    val npuExpected: Boolean,
    val npuLoaded: Boolean,
    val npuAnalyzedAt: Long,
    val npuInferenceMs: Long,
    val audioExpected: Boolean,
    val audioLoaded: Boolean,
    val audioAnalyzedAt: Long,
    val freeStorageBytes: Long,
    val clipStorageBytes: Long
)

/** 每分钟保存一条端侧健康统计，滚动保留 24 小时。 */
class HealthDiagnostics(context: Context) {
    companion object {
        private const val TAG = "HealthDiagnostics"
        private const val STORE_VERSION = 1
        private const val MAX_SAMPLES = 1_500
    }

    private val rootDir = File(context.filesDir, "health-diagnostics")
    private val storeFile = File(rootDir, "rolling-24h.json")
    private val samples = mutableListOf<DeviceHealthSample>()
    private val serviceStarts = mutableListOf<Long>()
    private var lastEncodedFrameCount = 0L
    private var lastSampleAt = 0L

    init {
        load()
    }

    @Synchronized
    fun recordServiceStart(now: Long = System.currentTimeMillis()) {
        serviceStarts += now
        trimLocked(now)
        persistLocked()
    }

    @Synchronized
    fun sample(input: DeviceHealthInput, now: Long = System.currentTimeMillis()): DeviceHealthSample {
        val elapsedMs = now - lastSampleAt
        val frameDelta = input.encodedFrameCount - lastEncodedFrameCount
        val fpsValid = lastSampleAt > 0L && elapsedMs > 0L && frameDelta >= 0L
        val fps = if (fpsValid) frameDelta * 1000f / elapsedMs else 0f
        val sample = DeviceHealthSample(
            sampledAt = now,
            cameraExpected = input.cameraExpected,
            cameraOn = input.cameraOn,
            frameFresh = !input.cameraExpected ||
                (input.frameCapturedAt > 0L && now - input.frameCapturedAt <= 5_000L),
            streamReady = !input.cameraExpected || input.streamReady,
            encodedFps = fps,
            encodedFpsValid = fpsValid && input.cameraExpected,
            npuExpected = input.npuExpected,
            npuLoaded = input.npuLoaded,
            npuFresh = !input.npuExpected ||
                (input.npuAnalyzedAt > 0L && now - input.npuAnalyzedAt <= 7_000L),
            npuInferenceMs = input.npuInferenceMs,
            audioExpected = input.audioExpected,
            audioLoaded = input.audioLoaded,
            audioFresh = !input.audioExpected ||
                (input.audioAnalyzedAt > 0L && now - input.audioAnalyzedAt <= 5_000L),
            freeStorageBytes = input.freeStorageBytes,
            clipStorageBytes = input.clipStorageBytes
        )
        samples += sample
        lastEncodedFrameCount = input.encodedFrameCount
        lastSampleAt = now
        trimLocked(now)
        persistLocked()
        return sample
    }

    @Synchronized
    fun summary(now: Long = System.currentTimeMillis()): DeviceHealthSummary =
        HealthSummaryPolicy.summarize(samples.toList(), serviceStarts.toList(), now)

    private fun trimLocked(now: Long) {
        val cutoff = now - HealthSummaryPolicy.WINDOW_MS
        samples.removeAll { it.sampledAt < cutoff || it.sampledAt > now + 60_000L }
        serviceStarts.removeAll { it < cutoff || it > now + 60_000L }
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
    }

    private fun load() = synchronized(this) {
        if (!storeFile.isFile) return@synchronized
        try {
            val root = JSONObject(storeFile.readText())
            val sampleArray = root.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until sampleArray.length()) {
                fromJson(sampleArray.optJSONObject(i) ?: continue)?.let(samples::add)
            }
            val startArray = root.optJSONArray("serviceStarts") ?: JSONArray()
            for (i in 0 until startArray.length()) {
                startArray.optLong(i).takeIf { it > 0L }?.let(serviceStarts::add)
            }
            samples.sortBy { it.sampledAt }
            serviceStarts.sort()
            trimLocked(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "读取 24 小时健康记录失败，将从空记录继续", e)
            samples.clear()
            serviceStarts.clear()
        }
    }

    private fun persistLocked() {
        try {
            rootDir.mkdirs()
            val root = JSONObject()
                .put("version", STORE_VERSION)
                .put("samples", JSONArray().apply { samples.forEach { put(toJson(it)) } })
                .put("serviceStarts", JSONArray(serviceStarts))
            val temp = File(rootDir, "rolling-24h.json.tmp")
            FileOutputStream(temp).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(storeFile)) {
                temp.copyTo(storeFile, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "保存 24 小时健康记录失败", e)
        }
    }

    private fun toJson(sample: DeviceHealthSample): JSONObject = JSONObject()
        .put("sampledAt", sample.sampledAt)
        .put("cameraExpected", sample.cameraExpected)
        .put("cameraOn", sample.cameraOn)
        .put("frameFresh", sample.frameFresh)
        .put("streamReady", sample.streamReady)
        .put("encodedFps", sample.encodedFps.toDouble())
        .put("encodedFpsValid", sample.encodedFpsValid)
        .put("npuExpected", sample.npuExpected)
        .put("npuLoaded", sample.npuLoaded)
        .put("npuFresh", sample.npuFresh)
        .put("npuInferenceMs", sample.npuInferenceMs)
        .put("audioExpected", sample.audioExpected)
        .put("audioLoaded", sample.audioLoaded)
        .put("audioFresh", sample.audioFresh)
        .put("freeStorageBytes", sample.freeStorageBytes)
        .put("clipStorageBytes", sample.clipStorageBytes)

    private fun fromJson(json: JSONObject): DeviceHealthSample? {
        val sampledAt = json.optLong("sampledAt")
        if (sampledAt <= 0L) return null
        return DeviceHealthSample(
            sampledAt = sampledAt,
            cameraExpected = json.optBoolean("cameraExpected"),
            cameraOn = json.optBoolean("cameraOn"),
            frameFresh = json.optBoolean("frameFresh"),
            streamReady = json.optBoolean("streamReady"),
            encodedFps = json.optDouble("encodedFps").toFloat(),
            encodedFpsValid = json.optBoolean("encodedFpsValid"),
            npuExpected = json.optBoolean("npuExpected"),
            npuLoaded = json.optBoolean("npuLoaded"),
            npuFresh = json.optBoolean("npuFresh"),
            npuInferenceMs = json.optLong("npuInferenceMs"),
            audioExpected = json.optBoolean("audioExpected"),
            audioLoaded = json.optBoolean("audioLoaded"),
            audioFresh = json.optBoolean("audioFresh"),
            freeStorageBytes = json.optLong("freeStorageBytes"),
            clipStorageBytes = json.optLong("clipStorageBytes")
        )
    }
}
