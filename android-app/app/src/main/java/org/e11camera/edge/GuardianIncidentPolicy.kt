package org.e11camera.edge

/** 事件会话的纯逻辑，便于在不启动 Android 的单元测试中验证边界。 */
internal object GuardianIncidentPolicy {
    const val MERGE_WINDOW_MS = 60_000L

    fun canMerge(incidentStartedAt: Long, now: Long): Boolean =
        incidentStartedAt > 0L && now >= incidentStartedAt && now - incidentStartedAt <= MERGE_WINDOW_MS

    fun title(motionCount: Int, cryCount: Int): String = when {
        motionCount > 0 && cryCount > 0 -> "宝宝守护：动作与疑似哭声"
        cryCount > 0 -> "宝宝守护：疑似哭声事件"
        else -> "宝宝守护：动作事件"
    }

    fun summary(motionCount: Int, cryCount: Int): String {
        val parts = mutableListOf<String>()
        if (motionCount > 0) parts += "画面动作 ×$motionCount"
        if (cryCount > 0) parts += "疑似哭声 ×$cryCount"
        return "1 分钟事件会话：${parts.joinToString(" · ")}"
    }
}
