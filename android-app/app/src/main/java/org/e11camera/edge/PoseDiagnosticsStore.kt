package org.e11camera.edge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PoseDiagnosticsSummary(
    val sampleCount: Int,
    val sampledMinuteCount: Int,
    val firstSampleAt: Long,
    val lastSampleAt: Long,
    val validPosePercent: Float,
    val averageKeypointValidity: Float,
    val averageInferenceMs: Float,
    val maximumInferenceMs: Long,
    val unknownCount: Int,
    val supineCount: Int,
    val sideCount: Int,
    val proneCount: Int,
    val faceRunCount: Int,
    val averageFaceInferenceMs: Float,
    val correctFeedbackCount: Int,
    val incorrectFeedbackCount: Int,
    val lastFeedbackAt: Long
)

private data class PoseMinuteBucket(
    val minuteStart: Long,
    var lastSampleAt: Long,
    var sampleCount: Int = 0,
    var validPoseCount: Int = 0,
    var keypointValidityTotal: Double = 0.0,
    var inferenceTotalMs: Long = 0L,
    var inferenceMaxMs: Long = 0L,
    var unknownCount: Int = 0,
    var supineCount: Int = 0,
    var sideCount: Int = 0,
    var proneCount: Int = 0,
    var faceRunCount: Int = 0,
    var faceInferenceTotalMs: Long = 0L
)

private data class PoseFeedbackEntry(
    val id: String,
    val occurredAt: Long,
    val value: String,
    val posture: String,
    val keypointValidity: Float,
    val faceStatus: String,
    val faceScore: Float
)

/**
 * Pose 影子模式的端侧滚动统计。每次推理只更新内存中的分钟桶，最多每分钟落盘一次；
 * 不保存截图、短片或可反推出家庭画面的原始关键点。
 */
class PoseDiagnosticsStore(context: Context) {
    companion object {
        private const val TAG = "PoseDiagnostics"
        private const val STORE_VERSION = 1
        private const val WINDOW_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_BUCKETS = 1_500
        private const val MAX_FEEDBACK = 200
        private const val MINUTE_MS = 60_000L
    }

    private val rootDir = File(context.filesDir, "pose-diagnostics")
    private val storeFile = File(rootDir, "rolling-24h.json")
    private val buckets = mutableListOf<PoseMinuteBucket>()
    private val feedback = mutableListOf<PoseFeedbackEntry>()
    private var lastPersistedMinute = Long.MIN_VALUE

    init {
        load()
    }

    @Synchronized
    fun record(snapshot: PoseAnalysisSnapshot) {
        if (snapshot.analyzedAt <= 0L) return
        val minute = snapshot.analyzedAt / MINUTE_MS * MINUTE_MS
        var bucket = buckets.lastOrNull { it.minuteStart == minute }
        if (bucket == null) {
            bucket = PoseMinuteBucket(minuteStart = minute, lastSampleAt = snapshot.analyzedAt)
            buckets += bucket
        }
        bucket.lastSampleAt = maxOf(bucket.lastSampleAt, snapshot.analyzedAt)
        bucket.sampleCount++
        if (snapshot.personCount > 0 && snapshot.keypointValidity > 0f) bucket.validPoseCount++
        bucket.keypointValidityTotal += snapshot.keypointValidity.toDouble()
        bucket.inferenceTotalMs += snapshot.poseInferenceMs
        bucket.inferenceMaxMs = maxOf(bucket.inferenceMaxMs, snapshot.poseInferenceMs)
        when (snapshot.rawPosture) {
            PostureLabel.UNKNOWN -> bucket.unknownCount++
            PostureLabel.SUPINE -> bucket.supineCount++
            PostureLabel.SIDE -> bucket.sideCount++
            PostureLabel.PRONE -> bucket.proneCount++
        }
        if (snapshot.faceInferenceMs > 0L) {
            bucket.faceRunCount++
            bucket.faceInferenceTotalMs += snapshot.faceInferenceMs
        }
        trimLocked(snapshot.analyzedAt)
        if (lastPersistedMinute != minute) {
            lastPersistedMinute = minute
            persistLocked()
        }
    }

    @Synchronized
    fun recordFeedback(value: String, snapshot: PoseAnalysisSnapshot): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized !in setOf("correct", "incorrect") || snapshot.analyzedAt <= 0L) return false
        feedback += PoseFeedbackEntry(
            id = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            value = normalized,
            posture = (snapshot.stablePosture.takeIf { it != PostureLabel.UNKNOWN }
                ?: snapshot.rawPosture).apiValue,
            keypointValidity = snapshot.keypointValidity,
            faceStatus = snapshot.faceStatus.apiValue,
            faceScore = snapshot.faceScore
        )
        while (feedback.size > MAX_FEEDBACK) feedback.removeAt(0)
        persistLocked()
        return true
    }

    @Synchronized
    fun summary(now: Long = System.currentTimeMillis()): PoseDiagnosticsSummary {
        trimLocked(now)
        val sampleCount = buckets.sumOf { it.sampleCount }
        val validCount = buckets.sumOf { it.validPoseCount }
        val inferenceTotal = buckets.sumOf { it.inferenceTotalMs }
        val faceRuns = buckets.sumOf { it.faceRunCount }
        val faceTotal = buckets.sumOf { it.faceInferenceTotalMs }
        val keypointTotal = buckets.sumOf { it.keypointValidityTotal }
        val correct = feedback.count { it.value == "correct" }
        val incorrect = feedback.count { it.value == "incorrect" }
        return PoseDiagnosticsSummary(
            sampleCount = sampleCount,
            sampledMinuteCount = buckets.size,
            firstSampleAt = buckets.firstOrNull()?.minuteStart ?: 0L,
            lastSampleAt = buckets.lastOrNull()?.lastSampleAt ?: 0L,
            validPosePercent = if (sampleCount > 0) validCount * 100f / sampleCount else 0f,
            averageKeypointValidity = if (sampleCount > 0) (keypointTotal / sampleCount).toFloat() else 0f,
            averageInferenceMs = if (sampleCount > 0) inferenceTotal.toFloat() / sampleCount else 0f,
            maximumInferenceMs = buckets.maxOfOrNull { it.inferenceMaxMs } ?: 0L,
            unknownCount = buckets.sumOf { it.unknownCount },
            supineCount = buckets.sumOf { it.supineCount },
            sideCount = buckets.sumOf { it.sideCount },
            proneCount = buckets.sumOf { it.proneCount },
            faceRunCount = faceRuns,
            averageFaceInferenceMs = if (faceRuns > 0) faceTotal.toFloat() / faceRuns else 0f,
            correctFeedbackCount = correct,
            incorrectFeedbackCount = incorrect,
            lastFeedbackAt = feedback.lastOrNull()?.occurredAt ?: 0L
        )
    }

    private fun trimLocked(now: Long) {
        val cutoff = now - WINDOW_MS
        buckets.removeAll { it.lastSampleAt < cutoff || it.minuteStart > now + MINUTE_MS }
        feedback.removeAll { it.occurredAt > now + MINUTE_MS }
        while (buckets.size > MAX_BUCKETS) buckets.removeAt(0)
        while (feedback.size > MAX_FEEDBACK) feedback.removeAt(0)
    }

    private fun load() = synchronized(this) {
        if (!storeFile.isFile) return@synchronized
        try {
            val root = JSONObject(storeFile.readText())
            val bucketArray = root.optJSONArray("buckets") ?: JSONArray()
            for (i in 0 until bucketArray.length()) {
                fromBucketJson(bucketArray.optJSONObject(i) ?: continue)?.let(buckets::add)
            }
            val feedbackArray = root.optJSONArray("feedback") ?: JSONArray()
            for (i in 0 until feedbackArray.length()) {
                fromFeedbackJson(feedbackArray.optJSONObject(i) ?: continue)?.let(feedback::add)
            }
            buckets.sortBy { it.minuteStart }
            feedback.sortBy { it.occurredAt }
            trimLocked(System.currentTimeMillis())
            lastPersistedMinute = buckets.lastOrNull()?.minuteStart ?: Long.MIN_VALUE
        } catch (e: Exception) {
            Log.w(TAG, "读取 Pose 影子统计失败，将从空记录继续", e)
            buckets.clear()
            feedback.clear()
        }
    }

    private fun persistLocked() {
        try {
            rootDir.mkdirs()
            val root = JSONObject()
                .put("version", STORE_VERSION)
                .put("buckets", JSONArray().apply { buckets.forEach { put(toJson(it)) } })
                .put("feedback", JSONArray().apply { feedback.forEach { put(toJson(it)) } })
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
            Log.w(TAG, "保存 Pose 影子统计失败", e)
        }
    }

    private fun toJson(bucket: PoseMinuteBucket): JSONObject = JSONObject()
        .put("minuteStart", bucket.minuteStart)
        .put("lastSampleAt", bucket.lastSampleAt)
        .put("sampleCount", bucket.sampleCount)
        .put("validPoseCount", bucket.validPoseCount)
        .put("keypointValidityTotal", bucket.keypointValidityTotal)
        .put("inferenceTotalMs", bucket.inferenceTotalMs)
        .put("inferenceMaxMs", bucket.inferenceMaxMs)
        .put("unknownCount", bucket.unknownCount)
        .put("supineCount", bucket.supineCount)
        .put("sideCount", bucket.sideCount)
        .put("proneCount", bucket.proneCount)
        .put("faceRunCount", bucket.faceRunCount)
        .put("faceInferenceTotalMs", bucket.faceInferenceTotalMs)

    private fun toJson(entry: PoseFeedbackEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("occurredAt", entry.occurredAt)
        .put("value", entry.value)
        .put("posture", entry.posture)
        .put("keypointValidity", entry.keypointValidity.toDouble())
        .put("faceStatus", entry.faceStatus)
        .put("faceScore", entry.faceScore.toDouble())

    private fun fromBucketJson(json: JSONObject): PoseMinuteBucket? {
        val minuteStart = json.optLong("minuteStart")
        if (minuteStart <= 0L) return null
        return PoseMinuteBucket(
            minuteStart = minuteStart,
            lastSampleAt = json.optLong("lastSampleAt", minuteStart),
            sampleCount = json.optInt("sampleCount"),
            validPoseCount = json.optInt("validPoseCount"),
            keypointValidityTotal = json.optDouble("keypointValidityTotal"),
            inferenceTotalMs = json.optLong("inferenceTotalMs"),
            inferenceMaxMs = json.optLong("inferenceMaxMs"),
            unknownCount = json.optInt("unknownCount"),
            supineCount = json.optInt("supineCount"),
            sideCount = json.optInt("sideCount"),
            proneCount = json.optInt("proneCount"),
            faceRunCount = json.optInt("faceRunCount"),
            faceInferenceTotalMs = json.optLong("faceInferenceTotalMs")
        )
    }

    private fun fromFeedbackJson(json: JSONObject): PoseFeedbackEntry? {
        val occurredAt = json.optLong("occurredAt")
        val value = json.optString("value")
        if (occurredAt <= 0L || value !in setOf("correct", "incorrect")) return null
        return PoseFeedbackEntry(
            id = json.optString("id", UUID.randomUUID().toString()),
            occurredAt = occurredAt,
            value = value,
            posture = json.optString("posture", PostureLabel.UNKNOWN.apiValue),
            keypointValidity = json.optDouble("keypointValidity").toFloat(),
            faceStatus = json.optString("faceStatus", FaceVisibilityStatus.IDLE.apiValue),
            faceScore = json.optDouble("faceScore").toFloat()
        )
    }
}
