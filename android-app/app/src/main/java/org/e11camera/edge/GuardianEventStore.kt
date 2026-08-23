package org.e11camera.edge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 一条可解释的宝宝守护事件。时间戳统一保存为 epoch millis，页面按东八区显示。 */
data class GuardianEventEntry(
    val id: String,
    val occurredAt: Long,
    val kind: String,
    val title: String,
    val summary: String,
    val decision: String,
    val reason: String,
    val personCount: Int,
    val adultLikePersonCount: Int,
    val supervisionState: String,
    val cryScore: Float? = null,
    val lastOccurredAt: Long = occurredAt,
    val motionCount: Int = 0,
    val cryCount: Int = 0,
    val notificationSent: Boolean = false,
    val observations: List<GuardianEventObservation> = emptyList(),
    val clipStatus: String = GuardianEventStore.CLIP_NONE,
    val clipFileName: String? = null,
    val clipDurationMs: Long = 0L,
    val clipSizeBytes: Long = 0L,
    val clipError: String? = null
)

data class GuardianEventObservation(
    val occurredAt: Long,
    val kind: String,
    val summary: String,
    val decision: String,
    val reason: String,
    val personCount: Int,
    val adultLikePersonCount: Int,
    val supervisionState: String,
    val cryScore: Float? = null,
    val visionRefreshStatus: String = "not_requested",
    val visionAnalyzedAt: Long = 0L,
    val visionSourceCapturedAt: Long = 0L,
    val visionInferenceMs: Long = 0L
)

data class GuardianEventMutation(
    val event: GuardianEventEntry,
    val created: Boolean,
    val shouldNotify: Boolean = false
)

data class GuardianClipStorageInfo(
    val clipCount: Int,
    val totalBytes: Long,
    val maxClipCount: Int,
    val maxBytes: Long
)

data class GuardianClipCleanupResult(
    val deletedCount: Int,
    val failedCount: Int,
    val freedBytes: Long
)

/**
 * 事件和短片都保存在应用私有目录 files/guardian-events/。
 *
 * 时间线最多保留 200 条；视频最多保留 60 段或 256 MiB，达到上限时只淘汰最旧
 * 的短片、保留解释记录。用户也可以在网页逐条删除短片。
 */
class GuardianEventStore(context: Context) {
    companion object {
        private const val TAG = "GuardianEventStore"
        private const val STORE_VERSION = 2
        private const val MAX_EVENTS = 200
        private const val MAX_OBSERVATIONS = 30
        const val MAX_CLIPS = 60
        const val MAX_CLIP_BYTES = 256L * 1024L * 1024L

        const val CLIP_NONE = "none"
        const val CLIP_PENDING = "pending"
        const val CLIP_READY = "ready"
        const val CLIP_FAILED = "failed"
        const val CLIP_DELETED = "deleted"
        const val CLIP_EXPIRED = "expired"
    }

    private val rootDir = File(context.filesDir, "guardian-events")
    private val clipsDir = File(rootDir, "clips")
    private val metadataFile = File(rootDir, "events.json")
    private val events = mutableListOf<GuardianEventEntry>()

    init {
        clipsDir.mkdirs()
        load()
    }

    @Synchronized
    fun addTriggered(
        kind: String,
        title: String,
        summary: String,
        decision: String,
        reason: String,
        personCount: Int,
        adultLikePersonCount: Int,
        supervisionState: String,
        cryScore: Float?
    ): GuardianEventEntry = addLocked(
        GuardianEventEntry(
            id = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            kind = kind,
            title = title,
            summary = summary,
            decision = decision,
            reason = reason,
            personCount = personCount,
            adultLikePersonCount = adultLikePersonCount,
            supervisionState = supervisionState,
            cryScore = cryScore,
            clipStatus = CLIP_PENDING
        )
    )

    /**
     * 将 1 分钟内的动作、疑似哭声和陪护变化归入同一事件会话。
     * 每个会话最多返回一次 shouldNotify=true，由调用方实际发送。
     */
    @Synchronized
    fun addOrMergeTriggered(
        kind: String,
        summary: String,
        decision: String,
        reason: String,
        personCount: Int,
        adultLikePersonCount: Int,
        supervisionState: String,
        cryScore: Float?,
        visionRefreshStatus: String,
        visionAnalyzedAt: Long,
        visionSourceCapturedAt: Long,
        visionInferenceMs: Long,
        canNotify: Boolean,
        now: Long = System.currentTimeMillis()
    ): GuardianEventMutation {
        require(kind == "motion" || kind == "cry") { "unsupported incident kind: $kind" }
        val observation = GuardianEventObservation(
            occurredAt = now,
            kind = kind,
            summary = summary,
            decision = decision,
            reason = reason,
            personCount = personCount,
            adultLikePersonCount = adultLikePersonCount,
            supervisionState = supervisionState,
            cryScore = cryScore,
            visionRefreshStatus = visionRefreshStatus,
            visionAnalyzedAt = visionAnalyzedAt,
            visionSourceCapturedAt = visionSourceCapturedAt,
            visionInferenceMs = visionInferenceMs
        )
        val existingIndex = events.indexOfFirst {
            it.kind == "incident" && GuardianIncidentPolicy.canMerge(it.occurredAt, now)
        }
        if (existingIndex < 0) {
            val motionCount = if (kind == "motion") 1 else 0
            val cryCount = if (kind == "cry") 1 else 0
            val stateIndex = events.indexOfFirst {
                it.kind == "state" && now >= it.lastOccurredAt && now - it.lastOccurredAt <= 2_000L
            }
            val recentState = stateIndex.takeIf { it >= 0 }?.let { events[it] }
            val initialObservations = buildList {
                recentState?.let {
                    add(
                        GuardianEventObservation(
                            occurredAt = it.lastOccurredAt,
                            kind = "state",
                            summary = it.summary,
                            decision = it.decision,
                            reason = it.reason,
                            personCount = it.personCount,
                            adultLikePersonCount = it.adultLikePersonCount,
                            supervisionState = it.supervisionState
                        )
                    )
                }
                add(observation)
            }
            if (stateIndex >= 0) events.removeAt(stateIndex)
            val event = GuardianEventEntry(
                id = UUID.randomUUID().toString(),
                occurredAt = recentState?.occurredAt ?: now,
                lastOccurredAt = now,
                kind = "incident",
                title = GuardianIncidentPolicy.title(motionCount, cryCount),
                summary = GuardianIncidentPolicy.summary(motionCount, cryCount),
                decision = decision,
                reason = reason,
                personCount = personCount,
                adultLikePersonCount = adultLikePersonCount,
                supervisionState = supervisionState,
                cryScore = cryScore,
                motionCount = motionCount,
                cryCount = cryCount,
                notificationSent = canNotify,
                observations = initialObservations,
                clipStatus = CLIP_PENDING
            )
            addLocked(event)
            return GuardianEventMutation(event, created = true, shouldNotify = canNotify)
        }

        val existing = events[existingIndex]
        val motionCount = existing.motionCount + if (kind == "motion") 1 else 0
        val cryCount = existing.cryCount + if (kind == "cry") 1 else 0
        val shouldNotify = canNotify && !existing.notificationSent
        val notificationSent = existing.notificationSent || shouldNotify
        val mergedObservation = if (canNotify && existing.notificationSent) {
            observation.copy(
                decision = "merged",
                reason = "$reason；同一事件会话已发送过通知，本次不重复推送"
            )
        } else {
            observation
        }
        val mergedReason = if (existing.notificationSent && !shouldNotify) {
            "本会话已处理过一次通知，后续信号仅合并记录。最近判断：$reason"
        } else {
            reason
        }
        val updated = existing.copy(
            lastOccurredAt = now,
            title = GuardianIncidentPolicy.title(motionCount, cryCount),
            summary = GuardianIncidentPolicy.summary(motionCount, cryCount),
            decision = if (notificationSent) "notified" else decision,
            reason = mergedReason,
            personCount = personCount,
            adultLikePersonCount = adultLikePersonCount,
            supervisionState = supervisionState,
            cryScore = cryScore ?: existing.cryScore,
            motionCount = motionCount,
            cryCount = cryCount,
            notificationSent = notificationSent,
            observations = (existing.observations + mergedObservation).takeLast(MAX_OBSERVATIONS)
        )
        events.removeAt(existingIndex)
        events.add(0, updated)
        persistLocked()
        return GuardianEventMutation(updated, created = false, shouldNotify = shouldNotify)
    }

    @Synchronized
    fun addState(
        title: String,
        summary: String,
        decision: String,
        reason: String,
        personCount: Int,
        adultLikePersonCount: Int,
        supervisionState: String,
        now: Long = System.currentTimeMillis()
    ): GuardianEventMutation {
        val existingIndex = events.indexOfFirst {
            it.kind == "incident" && GuardianIncidentPolicy.canMerge(it.occurredAt, now)
        }
        if (existingIndex >= 0) {
            val existing = events[existingIndex]
            val observation = GuardianEventObservation(
                occurredAt = now,
                kind = "state",
                summary = summary,
                decision = decision,
                reason = reason,
                personCount = personCount,
                adultLikePersonCount = adultLikePersonCount,
                supervisionState = supervisionState
            )
            val updated = existing.copy(
                lastOccurredAt = now,
                reason = "${existing.reason}；陪护变化：$reason",
                personCount = personCount,
                adultLikePersonCount = adultLikePersonCount,
                supervisionState = supervisionState,
                observations = (existing.observations + observation).takeLast(MAX_OBSERVATIONS)
            )
            events.removeAt(existingIndex)
            events.add(0, updated)
            persistLocked()
            return GuardianEventMutation(updated, created = false)
        }
        val event = GuardianEventEntry(
            id = UUID.randomUUID().toString(),
            occurredAt = now,
            lastOccurredAt = now,
            kind = "state",
            title = title,
            summary = summary,
            decision = decision,
            reason = reason,
            personCount = personCount,
            adultLikePersonCount = adultLikePersonCount,
            supervisionState = supervisionState
        )
        addLocked(event)
        return GuardianEventMutation(event, created = true)
    }

    @Synchronized
    fun list(limit: Int = 50): List<GuardianEventEntry> =
        events.take(limit.coerceIn(1, MAX_EVENTS)).toList()

    @Synchronized
    fun markClipReady(eventId: String, file: File, durationMs: Long) {
        if (!isManagedClip(file)) return
        val updated = updateLocked(eventId) {
            it.copy(
                clipStatus = CLIP_READY,
                clipFileName = file.name,
                clipDurationMs = durationMs,
                clipSizeBytes = file.length(),
                clipError = null
            )
        }
        if (!updated) {
            file.delete()
            return
        }
        enforceLimitsLocked()
        persistLocked()
    }

    @Synchronized
    fun markClipFailed(eventId: String, error: String) {
        updateLocked(eventId) {
            it.copy(
                clipStatus = CLIP_FAILED,
                clipFileName = null,
                clipDurationMs = 0L,
                clipSizeBytes = 0L,
                clipError = error.take(120)
            )
        }
        persistLocked()
    }

    /** 删除视频文件但保留时间线和当时的判断原因。 */
    @Synchronized
    fun deleteClip(eventId: String): Boolean {
        val index = events.indexOfFirst { it.id == eventId }
        if (index < 0) return false
        val current = events[index]
        current.clipFileName?.let { safeClipFile(it)?.delete() }
        events[index] = current.copy(
            clipStatus = CLIP_DELETED,
            clipFileName = null,
            clipSizeBytes = 0L,
            clipError = null
        )
        persistLocked()
        return true
    }

    /** 一键删除所有已生成的历史短片，保留事件时间、检测结果和判断原因。 */
    @Synchronized
    fun clearHistoryClips(): GuardianClipCleanupResult {
        var deletedCount = 0
        var failedCount = 0
        var freedBytes = 0L
        clipsDir.listFiles()
            ?.filter { it.extension == "mp4" && isManagedClip(it) }
            ?.forEach { file ->
                val size = file.length()
                if (file.delete() || !file.exists()) {
                    deletedCount++
                    freedBytes += size
                } else {
                    failedCount++
                }
            }

        for (i in events.indices) {
            val event = events[i]
            if (event.clipStatus != CLIP_READY) continue
            val fileStillExists = event.clipFileName
                ?.let { safeClipFile(it) }
                ?.isFile == true
            if (!fileStillExists) {
                events[i] = event.copy(
                    clipStatus = CLIP_DELETED,
                    clipFileName = null,
                    clipSizeBytes = 0L,
                    clipError = null
                )
            }
        }
        persistLocked()
        return GuardianClipCleanupResult(deletedCount, failedCount, freedBytes)
    }

    @Synchronized
    fun clipFile(eventId: String): File? {
        val event = events.firstOrNull { it.id == eventId && it.clipStatus == CLIP_READY }
            ?: return null
        val file = event.clipFileName?.let { safeClipFile(it) } ?: return null
        return file.takeIf { it.isFile }
    }

    @Synchronized
    fun storageInfo(): GuardianClipStorageInfo {
        val files = events.mapNotNull { event ->
            if (event.clipStatus == CLIP_READY) event.clipFileName?.let { safeClipFile(it) } else null
        }.filter { it.isFile }
        return GuardianClipStorageInfo(
            clipCount = files.size,
            totalBytes = files.sumOf { it.length() },
            maxClipCount = MAX_CLIPS,
            maxBytes = MAX_CLIP_BYTES
        )
    }

    internal fun newClipFile(eventId: String): File = File(clipsDir, "$eventId.mp4")

    @Synchronized
    private fun addLocked(event: GuardianEventEntry): GuardianEventEntry {
        events.add(0, event)
        while (events.size > MAX_EVENTS) {
            val removed = events.removeLast()
            removed.clipFileName?.let { safeClipFile(it)?.delete() }
        }
        persistLocked()
        return event
    }

    private fun updateLocked(
        eventId: String,
        block: (GuardianEventEntry) -> GuardianEventEntry
    ): Boolean {
        val index = events.indexOfFirst { it.id == eventId }
        if (index < 0) return false
        events[index] = block(events[index])
        return true
    }

    private fun load() {
        synchronized(this) {
            if (!metadataFile.isFile) return
            try {
                val root = JSONObject(metadataFile.readText())
                val array = root.optJSONArray("events") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val parsed = fromJson(array.optJSONObject(i) ?: continue) ?: continue
                    events.add(
                        if (parsed.clipStatus == CLIP_PENDING) {
                            parsed.copy(clipStatus = CLIP_FAILED, clipError = "设备重启前短片未完成")
                        } else parsed
                    )
                }
                events.sortByDescending { it.lastOccurredAt }
                enforceLimitsLocked()
                persistLocked()
            } catch (e: Exception) {
                Log.w(TAG, "读取事件记录失败，将从空时间线继续: ${e.message}")
                events.clear()
                enforceLimitsLocked()
                persistLocked()
            }
        }
    }

    private fun enforceLimitsLocked() {
        var kept = 0
        var bytes = 0L
        for (i in events.indices) {
            val event = events[i]
            if (event.clipStatus != CLIP_READY) continue
            val file = event.clipFileName?.let { safeClipFile(it) }
            if (file == null || !file.isFile) {
                events[i] = event.copy(
                    clipStatus = CLIP_EXPIRED,
                    clipFileName = null,
                    clipSizeBytes = 0L,
                    clipError = null
                )
                continue
            }
            val keep = kept < MAX_CLIPS && bytes + file.length() <= MAX_CLIP_BYTES
            if (keep) {
                kept++
                bytes += file.length()
            } else {
                file.delete()
                events[i] = event.copy(
                    clipStatus = CLIP_EXPIRED,
                    clipFileName = null,
                    clipSizeBytes = 0L,
                    clipError = null
                )
            }
        }
        val referenced = events.mapNotNullTo(mutableSetOf()) { event ->
            event.clipFileName?.takeIf { event.clipStatus == CLIP_READY }
        }
        clipsDir.listFiles()?.forEach { file ->
            if (file.extension == "tmp" || (file.extension == "mp4" && file.name !in referenced)) {
                file.delete()
            }
        }
    }

    private fun persistLocked() {
        try {
            rootDir.mkdirs()
            val array = JSONArray()
            events.forEach { array.put(toJson(it)) }
            val root = JSONObject().put("version", STORE_VERSION).put("events", array)
            val temp = File(rootDir, "events.json.tmp")
            FileOutputStream(temp).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(metadataFile)) {
                temp.copyTo(metadataFile, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "保存事件记录失败: ${e.message}")
        }
    }

    private fun isManagedClip(file: File): Boolean =
        try {
            file.canonicalFile.parentFile == clipsDir.canonicalFile && file.extension == "mp4"
        } catch (_: Exception) {
            false
        }

    private fun safeClipFile(name: String): File? {
        if (name.isBlank() || File(name).name != name || !name.endsWith(".mp4")) return null
        val file = File(clipsDir, name)
        return file.takeIf { isManagedClip(it) }
    }

    private fun toJson(event: GuardianEventEntry): JSONObject = JSONObject()
        .put("id", event.id)
        .put("occurredAt", event.occurredAt)
        .put("kind", event.kind)
        .put("title", event.title)
        .put("summary", event.summary)
        .put("decision", event.decision)
        .put("reason", event.reason)
        .put("personCount", event.personCount)
        .put("adultLikePersonCount", event.adultLikePersonCount)
        .put("supervisionState", event.supervisionState)
        .put("cryScore", event.cryScore ?: JSONObject.NULL)
        .put("lastOccurredAt", event.lastOccurredAt)
        .put("motionCount", event.motionCount)
        .put("cryCount", event.cryCount)
        .put("notificationSent", event.notificationSent)
        .put("observations", JSONArray().apply {
            event.observations.forEach { put(toJson(it)) }
        })
        .put("clipStatus", event.clipStatus)
        .put("clipFileName", event.clipFileName ?: JSONObject.NULL)
        .put("clipDurationMs", event.clipDurationMs)
        .put("clipSizeBytes", event.clipSizeBytes)
        .put("clipError", event.clipError ?: JSONObject.NULL)

    private fun fromJson(json: JSONObject): GuardianEventEntry? {
        val id = json.optString("id")
        val occurredAt = json.optLong("occurredAt")
        if (id.isBlank() || occurredAt <= 0L) return null
        val kind = json.optString("kind", "state")
        val observations = mutableListOf<GuardianEventObservation>()
        json.optJSONArray("observations")?.let { array ->
            for (i in 0 until array.length()) {
                fromObservationJson(array.optJSONObject(i) ?: continue)?.let(observations::add)
            }
        }
        return GuardianEventEntry(
            id = id,
            occurredAt = occurredAt,
            kind = kind,
            title = json.optString("title"),
            summary = json.optString("summary"),
            decision = json.optString("decision", "recorded"),
            reason = json.optString("reason"),
            personCount = json.optInt("personCount"),
            adultLikePersonCount = json.optInt("adultLikePersonCount"),
            supervisionState = json.optString("supervisionState", "unknown"),
            cryScore = if (json.isNull("cryScore")) null else json.optDouble("cryScore").toFloat(),
            lastOccurredAt = json.optLong("lastOccurredAt", occurredAt).coerceAtLeast(occurredAt),
            motionCount = if (json.has("motionCount")) {
                json.optInt("motionCount")
            } else if (kind == "motion") 1 else 0,
            cryCount = if (json.has("cryCount")) {
                json.optInt("cryCount")
            } else if (kind == "cry") 1 else 0,
            notificationSent = json.optBoolean("notificationSent", json.optString("decision") == "notified"),
            observations = observations.takeLast(MAX_OBSERVATIONS),
            clipStatus = json.optString("clipStatus", CLIP_NONE),
            clipFileName = json.optNullableString("clipFileName"),
            clipDurationMs = json.optLong("clipDurationMs"),
            clipSizeBytes = json.optLong("clipSizeBytes"),
            clipError = json.optNullableString("clipError")
        )
    }

    private fun toJson(observation: GuardianEventObservation): JSONObject = JSONObject()
        .put("occurredAt", observation.occurredAt)
        .put("kind", observation.kind)
        .put("summary", observation.summary)
        .put("decision", observation.decision)
        .put("reason", observation.reason)
        .put("personCount", observation.personCount)
        .put("adultLikePersonCount", observation.adultLikePersonCount)
        .put("supervisionState", observation.supervisionState)
        .put("cryScore", observation.cryScore ?: JSONObject.NULL)
        .put("visionRefreshStatus", observation.visionRefreshStatus)
        .put("visionAnalyzedAt", observation.visionAnalyzedAt)
        .put("visionSourceCapturedAt", observation.visionSourceCapturedAt)
        .put("visionInferenceMs", observation.visionInferenceMs)

    private fun fromObservationJson(json: JSONObject): GuardianEventObservation? {
        val occurredAt = json.optLong("occurredAt")
        if (occurredAt <= 0L) return null
        return GuardianEventObservation(
            occurredAt = occurredAt,
            kind = json.optString("kind", "state"),
            summary = json.optString("summary"),
            decision = json.optString("decision", "recorded"),
            reason = json.optString("reason"),
            personCount = json.optInt("personCount"),
            adultLikePersonCount = json.optInt("adultLikePersonCount"),
            supervisionState = json.optString("supervisionState", "unknown"),
            cryScore = if (json.isNull("cryScore")) null else json.optDouble("cryScore").toFloat(),
            visionRefreshStatus = json.optString("visionRefreshStatus", "not_requested"),
            visionAnalyzedAt = json.optLong("visionAnalyzedAt"),
            visionSourceCapturedAt = json.optLong("visionSourceCapturedAt"),
            visionInferenceMs = json.optLong("visionInferenceMs")
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
}
