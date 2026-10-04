package org.e11camera.edge

/** 用启动时间租约拒绝缺失、损坏、过期和上一轮启动遗留的观看标记。 */
object ViewerMarkerPolicy {
    const val MAX_AGE_MS = 35_000L

    fun isWatching(text: String, nowElapsedMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (nowElapsedMs < 0 || maxAgeMs < 0) return false
        val fields = text.trim().split(Regex("\\s+"))
        if (fields.size != 2 || fields[0] != "1") return false
        val updatedAtMs = fields[1].toLongOrNull() ?: return false
        // 启动时间倒退时，不能把上一轮启动尚未过期的 true 继续当作有效状态。
        if (updatedAtMs < 0 || updatedAtMs > nowElapsedMs) return false
        return nowElapsedMs - updatedAtMs <= maxAgeMs
    }
}
