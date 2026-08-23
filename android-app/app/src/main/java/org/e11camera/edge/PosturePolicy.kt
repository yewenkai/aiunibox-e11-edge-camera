package org.e11camera.edge

import java.util.ArrayDeque
import kotlin.math.sqrt

enum class PostureLabel(val apiValue: String) {
    UNKNOWN("unknown"),
    SUPINE("supine"),
    SIDE("side"),
    PRONE("prone")
}

enum class FaceVisibilityStatus(val apiValue: String) {
    DISABLED("disabled"),
    IDLE("idle"),
    NO_HEAD_CROP("no_head_crop"),
    VISIBLE("visible"),
    NOT_VISIBLE("not_visible"),
    MODEL_UNAVAILABLE("model_unavailable")
}

data class PosePerson(
    /** 归一化 [x, y, w, h, confidence]。 */
    val box: FloatArray,
    /** COCO 17 点，每点 [x, y, confidence]，x/y 已映射到原始画面 0..1。 */
    val keypoints: FloatArray
)

data class PostureClassification(
    val label: PostureLabel,
    val keypointValidity: Float,
    val faceKeypointValidity: Float
)

data class PoseAnalysisSnapshot(
    val analyzedAt: Long = 0L,
    val sourceCapturedAt: Long = 0L,
    val poseInferenceMs: Long = 0L,
    val rawPosture: PostureLabel = PostureLabel.UNKNOWN,
    val stablePosture: PostureLabel = PostureLabel.UNKNOWN,
    val keypointValidity: Float = 0f,
    val faceKeypointValidity: Float = 0f,
    val personCount: Int = 0,
    val adultLikePersonCount: Int = 0,
    val targetBox: FloatArray = FloatArray(0),
    val keypoints: FloatArray = FloatArray(0),
    val faceStatus: FaceVisibilityStatus = FaceVisibilityStatus.IDLE,
    val faceScore: Float = 0f,
    val faceInferenceMs: Long = 0L
)

data class PostureTransition(
    val previous: PostureLabel,
    val current: PostureLabel
)

/**
 * 只依赖 2D 关键点的保守启发式。它不声称识别医学意义上的睡姿，
 * 只把“脸部关键点可见性 + 肩髋几何”整理成可解释的影子标签。
 */
internal object PostureClassificationPolicy {
    const val KEYPOINT_COUNT = 17
    const val KEYPOINT_CONFIDENCE = 0.35f

    private const val NOSE = 0
    private const val LEFT_EYE = 1
    private const val RIGHT_EYE = 2
    private const val LEFT_EAR = 3
    private const val RIGHT_EAR = 4
    private const val LEFT_SHOULDER = 5
    private const val RIGHT_SHOULDER = 6
    private const val LEFT_HIP = 11
    private const val RIGHT_HIP = 12

    fun classify(person: PosePerson?): PostureClassification {
        if (person == null || person.keypoints.size < KEYPOINT_COUNT * 3) {
            return PostureClassification(PostureLabel.UNKNOWN, 0f, 0f)
        }
        val points = person.keypoints
        val validCount = (0 until KEYPOINT_COUNT).count { valid(points, it) }
        val faceIndices = intArrayOf(NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR)
        val faceValid = faceIndices.count { valid(points, it) }
        val validity = validCount / KEYPOINT_COUNT.toFloat()
        val faceValidity = faceValid / faceIndices.size.toFloat()
        if (validity < 0.18f) {
            return PostureClassification(PostureLabel.UNKNOWN, validity, faceValidity)
        }

        val shouldersVisible = valid(points, LEFT_SHOULDER) && valid(points, RIGHT_SHOULDER)
        val hipsVisible = valid(points, LEFT_HIP) && valid(points, RIGHT_HIP)
        val noseVisible = valid(points, NOSE)
        val bothEyes = valid(points, LEFT_EYE) && valid(points, RIGHT_EYE)
        val bothEars = valid(points, LEFT_EAR) && valid(points, RIGHT_EAR)

        val label = when {
            // 背部朝向镜头的粗候选：身体主干清楚，但鼻眼耳几乎全部缺失。
            shouldersVisible && hipsVisible && !noseVisible && faceValid <= 1 -> PostureLabel.PRONE

            // 正脸或近似正脸可见，按仰卧候选记录。
            noseVisible && faceValid >= 3 && shouldersVisible -> PostureLabel.SUPINE
            noseVisible && (bothEyes || bothEars) -> PostureLabel.SUPINE

            // 单侧脸或肩线相对躯干明显收窄，按侧卧候选记录。
            shouldersVisible && hipsVisible && (
                faceValid in 1..3 || shoulderToTorsoRatio(points) < 0.55f
            ) -> PostureLabel.SIDE
            faceValid in 1..3 && validCount >= 5 -> PostureLabel.SIDE

            else -> PostureLabel.UNKNOWN
        }
        return PostureClassification(label, validity, faceValidity)
    }

    fun isRisk(label: PostureLabel, sideRiskEnabled: Boolean): Boolean =
        label == PostureLabel.PRONE || (sideRiskEnabled && label == PostureLabel.SIDE)

    private fun valid(points: FloatArray, index: Int): Boolean {
        val offset = index * 3
        val x = points[offset]
        val y = points[offset + 1]
        val confidence = points[offset + 2]
        return confidence >= KEYPOINT_CONFIDENCE && x in 0f..1f && y in 0f..1f
    }

    private fun shoulderToTorsoRatio(points: FloatArray): Float {
        val shoulderSpan = distance(points, LEFT_SHOULDER, RIGHT_SHOULDER)
        val shoulderMidX = (points[LEFT_SHOULDER * 3] + points[RIGHT_SHOULDER * 3]) / 2f
        val shoulderMidY = (points[LEFT_SHOULDER * 3 + 1] + points[RIGHT_SHOULDER * 3 + 1]) / 2f
        val hipMidX = (points[LEFT_HIP * 3] + points[RIGHT_HIP * 3]) / 2f
        val hipMidY = (points[LEFT_HIP * 3 + 1] + points[RIGHT_HIP * 3 + 1]) / 2f
        val torso = distance(shoulderMidX, shoulderMidY, hipMidX, hipMidY)
        return if (torso <= 0.001f) Float.POSITIVE_INFINITY else shoulderSpan / torso
    }

    private fun distance(points: FloatArray, a: Int, b: Int): Float = distance(
        points[a * 3], points[a * 3 + 1], points[b * 3], points[b * 3 + 1]
    )

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}

/** 最近 6 次中至少 5 次一致才更新稳定姿态，避免单帧关键点抖动。 */
internal class PostureStateMachine(
    private val windowSize: Int = 6,
    private val confirmVotes: Int = 5
) {
    private val history = ArrayDeque<PostureLabel>(windowSize)

    var state: PostureLabel = PostureLabel.UNKNOWN
        private set

    init {
        require(windowSize > 0)
        require(confirmVotes in 1..windowSize)
    }

    fun push(label: PostureLabel): PostureTransition? {
        if (history.size == windowSize) history.removeFirst()
        history.addLast(label)
        if (history.size < confirmVotes) return null

        val candidate = PostureLabel.values()
            .map { it to history.count { observed -> observed == it } }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= confirmVotes }
            ?.first
            ?: return null
        if (candidate == state) return null
        val previous = state
        state = candidate
        return PostureTransition(previous, candidate)
    }

    fun reset() {
        history.clear()
        state = PostureLabel.UNKNOWN
    }
}
