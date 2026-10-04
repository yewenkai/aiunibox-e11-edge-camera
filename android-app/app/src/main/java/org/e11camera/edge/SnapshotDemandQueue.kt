package org.e11camera.edge

/**
 * 把等待新快照的请求合并成最多一个在途批次，不依赖 Android 或系统时钟。
 *
 * 本类不负责同步；调用方必须在同一个 jpegMonitor 锁内调用所有方法和读取属性。
 * deadlineElapsedMs 与 nowElapsedMs 必须来自同一个单调时钟。
 *
 * claim 冻结当前批次覆盖的最大序号，提交之后的新请求只能由下一批完成。
 * 请求过期或取消只移除等待需求；已经提交的采集仍须由 complete/release 结束，
 * 或在相机会话失效时由 clear 丢弃。等待者退出时须调用 cancel 清理完成记录。
 */
class SnapshotDemandQueue {
    private var nextSequence = 0L
    private var completedSequence = 0L
    private val pending = linkedMapOf<Long, Long>()
    private val completed = mutableSetOf<Long>()

    var activeSequence: Long? = null
        private set

    fun enqueue(deadlineElapsedMs: Long): Long {
        check(nextSequence < Long.MAX_VALUE) { "Snapshot sequence exhausted" }
        val sequence = ++nextSequence
        pending[sequence] = deadlineElapsedMs
        return sequence
    }

    /** 清理过期请求；已有采集在途时，不允许提交另一批。 */
    fun claim(nowElapsedMs: Long): Long? {
        removeExpired(nowElapsedMs)
        if (activeSequence != null) return null
        return pending.keys.lastOrNull()?.also { activeSequence = it }
    }

    /** 仅完成当前批次中仍在等待的请求；迟到或重复完成回调返回 false。 */
    fun complete(coveredSequence: Long): Boolean {
        if (activeSequence != coveredSequence) return false
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val sequence = iterator.next().key
            if (sequence <= coveredSequence) {
                completed.add(sequence)
                iterator.remove()
            }
        }
        completedSequence = maxOf(completedSequence, coveredSequence)
        activeSequence = null
        return true
    }

    /** 释放匹配的在途批次，保留未过期的需求供下一次 claim 重试。 */
    fun release(coveredSequence: Long) {
        if (activeSequence == coveredSequence) activeSequence = null
    }

    /** 取消等待，或在等待者返回后回收其完成记录。 */
    fun cancel(sequence: Long) {
        pending.remove(sequence)
        completed.remove(sequence)
    }

    /** 会话失效时丢弃全部状态；保留单调序号，旧回调不能匹配新批次。 */
    fun clear() {
        pending.clear()
        completed.clear()
        activeSequence = null
        completedSequence = 0L
    }

    fun isPending(sequence: Long): Boolean = pending.containsKey(sequence)

    fun isCompleted(sequence: Long): Boolean =
        sequence <= completedSequence && completed.contains(sequence)

    fun hasDemand(nowElapsedMs: Long): Boolean {
        removeExpired(nowElapsedMs)
        return pending.isNotEmpty()
    }

    private fun removeExpired(nowElapsedMs: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= nowElapsedMs) iterator.remove()
        }
    }
}
