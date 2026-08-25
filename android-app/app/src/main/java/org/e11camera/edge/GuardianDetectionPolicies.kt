package org.e11camera.edge

import kotlin.math.max
import kotlin.math.min

/**
 * 哭声的轻量时间投票：强命中立即成立；较弱但连续的候选需要最近 3 窗中至少 2 窗成立。
 * 配合 50% 重叠音频窗，降低哭声刚好跨越窗口边界时被稀释而漏检的概率。
 */
class CryTemporalPolicy(
    private val weakThresholdRatio: Float = 0.72f,
    private val voteWindow: Int = 3,
    private val weakVotesRequired: Int = 2
) {
    private val weakVotes = ArrayDeque<Boolean>()

    @Synchronized
    fun push(score: Float, threshold: Float): Boolean {
        if (!score.isFinite() || !threshold.isFinite() || threshold <= 0f) {
            reset()
            return false
        }
        val normalizedScore = score.coerceAtLeast(0f)
        val strong = normalizedScore >= threshold
        val weak = normalizedScore >= threshold * weakThresholdRatio.coerceIn(0f, 1f)
        weakVotes.addLast(weak)
        while (weakVotes.size > voteWindow.coerceAtLeast(1)) weakVotes.removeFirst()
        val required = weakVotesRequired.coerceAtLeast(1)
        return strong || (weakVotes.size >= required && weakVotes.count { it } >= required)
    }

    @Synchronized
    fun reset() {
        weakVotes.clear()
    }
}

/** 固定机位成人体型证据，按人体框底部位置对远处目标做保守透视补偿。 */
object AdultEvidencePolicy {
    fun isAdultLike(box: FloatArray, minHeight: Float, minArea: Float): Boolean {
        if (box.size < 4 || minHeight <= 0f || minArea <= 0f) return false
        val x = box[0]
        val y = box[1]
        val width = box[2]
        val height = box[3]
        if (listOf(x, y, width, height, minHeight, minArea).any { !it.isFinite() }) return false
        if (x !in 0f..1f || y !in 0f..1f || width <= 0f || height <= 0f) return false

        val bottom = (y + height).coerceIn(0f, 1f)
        // 画面上方通常离镜头更远：最多把线性高度门槛放宽到 65%，
        // 面积门槛按同一线性比例的平方缩放。靠近画面底部仍使用完整门槛。
        val perspective = (0.65f + 0.35f * bottom).coerceIn(0.65f, 1f)
        val adjustedHeight = minHeight * perspective
        val adjustedArea = minArea * perspective * perspective
        return height >= adjustedHeight && width * height >= adjustedArea
    }
}

data class MotionSignalDecision(
    val regularActive: Boolean,
    val strongActive: Boolean,
    val regularThreshold: Double,
    val strongThreshold: Double
)

/** 把 1 秒短帧差和约 5 秒累计帧差合并成常规、强动作两条触发证据。 */
object MotionSignalPolicy {
    fun evaluate(shortRatio: Double, lagRatio: Double, baseThreshold: Double): MotionSignalDecision {
        val threshold = baseThreshold.coerceIn(0.001, 1.0)
        val lagThreshold = (threshold * 1.20).coerceAtMost(1.0)
        val strongThreshold = max(threshold * 3.0, threshold + 0.08).coerceAtMost(0.35)
        return MotionSignalDecision(
            regularActive = shortRatio >= threshold || lagRatio >= lagThreshold,
            strongActive = shortRatio >= strongThreshold || lagRatio >= strongThreshold,
            regularThreshold = threshold,
            strongThreshold = strongThreshold
        )
    }
}

/** 多人 Pose 目标选择：优先延续上一帧重叠目标，失配时才回退到较小非成人候选。 */
object PoseTargetPolicy {
    private const val TRACK_IOU_THRESHOLD = 0.15f

    fun select(
        persons: List<PosePerson>,
        previousBox: FloatArray,
        isAdultLike: (PosePerson) -> Boolean
    ): PosePerson? {
        if (persons.isEmpty()) return null
        // 只要仍有非成人体型候选，就不允许新出现的成人框抢走姿态目标。
        val nonAdult = persons.filterNot(isAdultLike)
        val candidates = nonAdult.ifEmpty { persons }
        if (previousBox.size >= 4) {
            val tracked = candidates
                .map { it to boxIou(previousBox, it.box) }
                .maxByOrNull { it.second }
            if (tracked != null && tracked.second >= TRACK_IOU_THRESHOLD) return tracked.first
        }
        if (candidates.size == 1) return candidates.single()
        return candidates.minByOrNull { boxArea(it.box) }
    }

    internal fun boxIou(a: FloatArray, b: FloatArray): Float {
        if (!validBox(a) || !validBox(b)) return 0f
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[0] + a[2], b[0] + b[2])
        val y2 = min(a[1] + a[3], b[1] + b[3])
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = boxArea(a) + boxArea(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun validBox(box: FloatArray): Boolean =
        box.size >= 4 && box.take(4).all { it.isFinite() } && box[2] > 0f && box[3] > 0f

    private fun boxArea(box: FloatArray): Float =
        if (validBox(box)) box[2] * box[3] else Float.MAX_VALUE
}
