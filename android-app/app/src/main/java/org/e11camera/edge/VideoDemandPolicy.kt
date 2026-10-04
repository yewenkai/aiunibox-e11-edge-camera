package org.e11camera.edge

/** Pure runtime policy; caller serializes access and supplies a monotonic clock. */
class VideoDemandPolicy(
    private val idleGraceMs: Long = 30_000L,
    private val externalLeaseMs: Long = 35_000L
) {
    enum class Mode(val value: String, val needsVideo: Boolean) {
        ACTIVE("active", true), STANDBY("standby", false),
        MANUAL_PAUSED("manual_paused", false), PRIVACY_PAUSED("privacy_paused", false)
    }
    data class Input(
        val captureEnabled: Boolean,
        val privacy: Boolean,
        val visualGuardian: Boolean,
        val watching: Boolean,
        val preview: Boolean = false
    )

    private var nextLease = 0L
    private var idleUntilMs = 0L
    private val requests = mutableSetOf<Long>()
    private val external = mutableMapOf<String, Long>()

    fun touch(nowMs: Long) { idleUntilMs = nowMs + idleGraceMs }
    fun acquire(nowMs: Long): Long = (++nextLease).also { requests.add(it); touch(nowMs) }
    fun release(lease: Long, nowMs: Long) { if (requests.remove(lease)) touch(nowMs) }
    fun renewExternal(owner: String, enabled: Boolean, nowMs: Long) {
        if (enabled) external[owner] = nowMs + externalLeaseMs else external.remove(owner)
        touch(nowMs)
    }
    fun clear() { requests.clear(); external.clear(); idleUntilMs = 0L }

    fun mode(input: Input, nowMs: Long): Mode {
        external.entries.removeAll { it.value <= nowMs }
        if (input.privacy) return Mode.PRIVACY_PAUSED
        if (!input.captureEnabled) return Mode.MANUAL_PAUSED
        return if (input.visualGuardian || input.watching || input.preview || requests.isNotEmpty() ||
            external.isNotEmpty() || nowMs < idleUntilMs) Mode.ACTIVE else Mode.STANDBY
    }

    /** Only schedules lease/grace expiry; persistent consumers use event callbacks. */
    fun nextCheckAt(input: Input, nowMs: Long): Long? {
        if (!input.captureEnabled || input.privacy || input.visualGuardian || input.watching ||
            input.preview || requests.isNotEmpty()) return null
        return (external.values + listOf(idleUntilMs)).filter { it > nowMs }.minOrNull()
    }

    val requestCount: Int get() = requests.size
    fun hasRequest(lease: Long): Boolean = lease in requests
    val externalCount: Int get() = external.size
}
