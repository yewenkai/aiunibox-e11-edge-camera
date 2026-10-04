package org.e11camera.edge

/** 告警共用一个单调时钟截止时间；排队、取帧都消耗同一份等待预算。 */
internal object AlertVisionRefreshPolicy {
    const val DEFAULT_TIMEOUT_MS = 600L
    const val MAX_CAPTURE_WAIT_MS = 450L
    const val MAX_SOURCE_AGE_MS = 1_500L
    const val RECENT_SOURCE_REUSE_MS = 500L

    fun maxAgeForAlert(refreshStatus: String): Long = when (refreshStatus) {
        "refreshed", "scheduled", "warmup", "current_state", "supervision_transition" -> MAX_SOURCE_AGE_MS
        else -> RECENT_SOURCE_REUSE_MS
    }

    fun remainingMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    fun captureWaitMs(remainingMs: Long, previousInferenceMs: Long): Long {
        // 未预热也留出 150ms；已预热按最近一次耗时加 40ms 余量，至少预留 90ms。
        val inferenceReserve = if (previousInferenceMs > 0L) {
            (previousInferenceMs + 40L).coerceIn(90L, 300L)
        } else 150L
        return (remainingMs - inferenceReserve).coerceIn(0L, MAX_CAPTURE_WAIT_MS)
    }

    fun sourceAgeMs(
        nowWallMs: Long,
        sourceWallMs: Long,
        nowElapsedMs: Long,
        sourceElapsedMs: Long
    ): Long {
        if (sourceWallMs <= 0L) return -1L
        val age = if (sourceElapsedMs > 0L) nowElapsedMs - sourceElapsedMs
        else nowWallMs - sourceWallMs
        return if (age >= 0L) age else -1L
    }

    fun sourceUsable(ageMs: Long, maxAgeMs: Long = MAX_SOURCE_AGE_MS): Boolean =
        ageMs in 0L..maxAgeMs

    fun safeDecision(
        snapshot: AlertVisionSnapshot,
        ageMs: Long,
        detectorRunning: Boolean,
        maxAgeMs: Long = MAX_SOURCE_AGE_MS
    ): AlertVisionSnapshot {
        val sourceLimit = minOf(maxAgeMs, snapshot.sourceMaxAgeMs)
        if (detectorRunning && sourceUsable(ageMs, sourceLimit)) return snapshot.copy(sourceAgeMs = ageMs)
        // 时间字段仍指向真实分析源；旧框和陪护缓冲不能当成本次有效成人证据。
        return snapshot.copy(
            personCount = 0, adultLikePersonCount = 0,
            supervisionState = SupervisionStateMachine.State.UNKNOWN,
            notificationsSuppressed = false, sourceAgeMs = ageMs,
            degradedReason = if (detectorRunning) "source_expired" else "detector_stopped"
        )
    }
}
