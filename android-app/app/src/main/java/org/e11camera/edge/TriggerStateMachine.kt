package org.e11camera.edge

/**
 * 宝宝守护共用触发状态机：滑动窗口内"有动静"的采样数达到阈值即触发，
 * 连续安静一段时间后回落。当前由动作检测的普通与强动作通道分别使用；
 * 哭声使用独立的重叠窗口时间投票。
 */
class TriggerStateMachine(
    private val triggerWindow: Int = TRIGGER_WINDOW,
    private val triggerMinActive: Int = TRIGGER_MIN_ACTIVE,
    private val quietReset: Int = QUIET_RESET
) {
    companion object {
        /** 最近窗口内 ≥ 这么多秒有动静则触发 */
        const val TRIGGER_WINDOW = 15
        const val TRIGGER_MIN_ACTIVE = 8

        /** 连续安静这么多样本后解除触发 */
        const val QUIET_RESET = 20
    }

    private val window = ArrayDeque<Boolean>()

    @Volatile
    var isActive: Boolean = false
        private set

    /** 推入一个采样，返回 true 表示这次 push 触发了上升沿。 */
    @Synchronized
    fun push(active: Boolean): Boolean {
        window.addLast(active)
        while (window.size > quietReset) window.removeFirst()
        if (!isActive) {
            val recent = window.toList().takeLast(triggerWindow)
            if (recent.size >= triggerWindow &&
                recent.count { it } >= triggerMinActive
            ) {
                isActive = true
                return true
            }
        } else {
            val recent = window.toList().takeLast(quietReset)
            if (recent.size >= quietReset && recent.all { !it }) {
                isActive = false
            }
        }
        return false
    }

    @Synchronized
    fun reset() {
        window.clear()
        isActive = false
    }
}
