package org.e11camera.edge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 宝宝守护：RK3566 NPU 端侧视觉补强（NPU_ROADMAP 阶段 1：有人/无人）。
 *
 * YOLOv8n RKNN 模型跑在 0.8 TOPS NPU 上，每 2 秒分析一帧快照，
 * 输出 personPresent / 全部人体框 / 人数，并驱动陪护状态机。
 * 与编码链路完全解耦（复用 JPEG 快照，不触碰 Camera2 回调线程）。
 *
 * 依赖：filesDir/yolov8n_rk3566.rknn（部署者自行转换，仓库不分发权重）、
 * APK 内置 librknnrt.so（airockchip v2.3.0）与 libe11npu.so（JNI 桥）。
 */
class NpuDetector(
    private val context: android.content.Context,
    private val streamer: CameraStreamer,
    private val onSupervisionChanged: (
        previous: SupervisionStateMachine.State,
        current: SupervisionStateMachine.State,
        observation: SupervisionStateMachine.Observation
    ) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "NpuDetector"
        private const val MODEL_FILE = "yolov8n_rk3566.rknn"
        private const val SAMPLE_INTERVAL_SEC = 2L
        private const val INPUT_SIZE = 640

        /** COCO class 0 = person；只关心人 */
        private const val PERSON_CLASS = 0
        private const val CONF_THRESHOLD = 0.35f
        private const val NMS_IOU = 0.45f
        private const val DFL_CHANNELS = 64
        private const val N_CLASSES = 80
        private const val ENTER_WINDOW = 3
        private const val ENTER_HITS = 2
        private const val LEAVE_DELAY_MS = 10_000L

        // 固定看护机位下的成人体型启发式；只用于陪护上下文，不宣称年龄分类。
        private const val ADULT_LIKE_MIN_HEIGHT = 0.48f
        private const val ADULT_LIKE_MIN_AREA = 0.14f
    }

    private val running = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val recentDetections = ArrayDeque<Boolean>(ENTER_WINDOW)
    private val supervision = SupervisionStateMachine()
    private var scheduler: ScheduledExecutorService? = null

    private var handle = 0L
    private var inputW = INPUT_SIZE
    private var inputH = INPUT_SIZE
    private var outputCount = 0

    @Volatile var modelLoaded: Boolean = false
        private set

    @Volatile var personPresent: Boolean = false
        private set

    @Volatile var personConfidence: Float = 0f
        private set

    /** 归一化 [x, y, w, h]，无目标时为空数组 */
    @Volatile var personBox: FloatArray = FloatArray(0)
        private set

    /** 当前一次 NPU 推理得到的全部人体框，元素为 [x,y,w,h,confidence]。 */
    @Volatile var personBoxes: List<FloatArray> = emptyList()
        private set

    @Volatile var personCount: Int = 0
        private set

    @Volatile var adultLikePersonCount: Int = 0
        private set

    @Volatile var caregiverEvidenceNow: Boolean = false
        private set

    val supervisionState: SupervisionStateMachine.State
        get() = supervision.state

    val notificationsSuppressed: Boolean
        get() = caregiverEvidenceNow || supervision.notificationsSuppressed

    @Volatile var lastSeenAt: Long = 0
        private set

    @Synchronized
    fun start() {
        if (running.get()) return
        if (!loadNativeAndModel()) return
        val executor = Executors.newSingleThreadScheduledExecutor()
        scheduler = executor
        running.set(true)
        executor.scheduleAtFixedRate({ tick() }, 3, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "NPU 检测已启动（YOLOv8n @${inputW}×${inputH}，每 ${SAMPLE_INTERVAL_SEC}s 一帧）")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && handle == 0L) return
        scheduler?.shutdownNow()
        scheduler = null
        synchronized(inferenceLock) {
            destroyModel()
            resetPresence()
        }
        Log.i(TAG, "NPU 检测已停止")
    }

    private fun loadNativeAndModel(): Boolean {
        try {
            System.loadLibrary("rknnrt")
            System.loadLibrary("e11npu")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "native 库加载失败，NPU 检测停用: ${e.message}")
            return false
        }
        val modelFile = File(context.filesDir, MODEL_FILE)
        if (!modelFile.exists()) {
            Log.w(TAG, "未找到 $MODEL_FILE，NPU 检测停用")
            return false
        }
        handle = nativeCreate(modelFile.readBytes())
        if (handle == 0L) {
            Log.w(TAG, "rknn_init 失败，NPU 检测停用")
            return false
        }
        val q = nativeQuery(handle)
        if (q.size < 6 || q[0] != 1 || q[1] != 1 || q[2] !in setOf(6, 9)) {
            Log.w(TAG, "模型输入输出不符合 Rockchip YOLOv8 优化版契约: ${q.joinToString()}")
            destroyModel()
            return false
        }
        outputCount = q[2]
        inputW = q[3]
        inputH = q[4]
        modelLoaded = true
        return true
    }

    private fun destroyModel() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
        modelLoaded = false
        outputCount = 0
    }

    private fun tick() {
        if (!running.get()) return
        try {
            synchronized(inferenceLock) {
                if (!running.get() || handle == 0L) return
                val jpeg = streamer.latestJpeg
                if (jpeg.isEmpty()) return
                val t0 = System.currentTimeMillis()
                val boxes = detectPersons(jpeg)
                val cost = System.currentTimeMillis() - t0
                updatePresence(boxes, cost)
            }
        } catch (e: Exception) {
            Log.w(TAG, "tick 异常", e)
        }
    }

    /** JPEG → letterbox 640 → NPU 推理 → yolov8 解码 → NMS → person 框（归一化 x,y,w,h,conf）。 */
    private fun detectPersons(jpeg: ByteArray): List<FloatArray> {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return emptyList()
        val srcW = bmp.width
        val srcH = bmp.height
        val scale = min(inputW.toFloat() / srcW, inputH.toFloat() / srcH)
        val nw = max(1, (srcW * scale).toInt())
        val nh = max(1, (srcH * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        if (scaled !== bmp) bmp.recycle()
        val letterboxed = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        // 与 Rockchip model zoo 的 RKNN 示例一致，letterbox 空白区填黑。
        canvas.drawColor(0xFF000000.toInt())
        val padX = (inputW - nw) / 2f
        val padY = (inputH - nh) / 2f
        canvas.drawBitmap(scaled, padX, padY, null)
        scaled.recycle()

        val px = IntArray(inputW * inputH)
        letterboxed.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
        letterboxed.recycle()
        val rgb = ByteArray(inputW * inputH * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (c shr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (c and 0xFF).toByte()
        }

        val outputs = nativeRun(handle, rgb, inputW, inputH) ?: return emptyList()
        if (outputs.size != outputCount || outputs.size !in setOf(6, 9)) return emptyList()
        val candidates = mutableListOf<FloatArray>()
        val outputPerBranch = outputs.size / 3
        for (branch in 0 until 3) {
            val stride = 1 shl (branch + 3) // 8, 16, 32
            val gridW = inputW / stride
            val gridH = inputH / stride
            val offset = branch * outputPerBranch
            decodeBranch(outputs[offset], outputs[offset + 1], gridW, gridH, stride, candidates)
        }
        val kept = nms(candidates)
        // letterbox 坐标 → 原图归一化坐标
        val mapped = kept.map { b ->
            val x1 = ((b[0] - padX) / scale / srcW).coerceIn(0f, 1f)
            val y1 = ((b[1] - padY) / scale / srcH).coerceIn(0f, 1f)
            val x2 = ((b[2] - padX) / scale / srcW).coerceIn(0f, 1f)
            val y2 = ((b[3] - padY) / scale / srcH).coerceIn(0f, 1f)
            floatArrayOf(x1, y1, x2 - x1, y2 - y1, b[4])
        }.filter { it[2] > 0.01f && it[3] > 0.01f }
        return removeNestedPersonBoxes(mapped)
    }

    /**
     * Rockchip 优化版单尺度输出：box=(1,64,H,W)，class=(1,80,H,W)，均为 NCHW。
     * box 中每个方向的 16 个值仍是 DFL logits，需要在 CPU 上 softmax 后求期望。
     */
    private fun decodeBranch(
        box: FloatArray,
        scores: FloatArray,
        gridW: Int,
        gridH: Int,
        stride: Int,
        sink: MutableList<FloatArray>
    ) {
        val cells = gridW * gridH
        if (box.size != DFL_CHANNELS * cells || scores.size != N_CLASSES * cells) return
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val cell = gy * gridW + gx
                val personScore = scores[PERSON_CLASS * cells + cell]
                if (personScore < CONF_THRESHOLD) continue
                val dist = FloatArray(4)
                for (side in 0 until 4) {
                    val baseChannel = side * 16
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (k in 0 until 16) {
                        maxLogit = max(maxLogit, box[(baseChannel + k) * cells + cell])
                    }
                    var denominator = 0.0
                    var weighted = 0.0
                    for (k in 0 until 16) {
                        val probability = exp((box[(baseChannel + k) * cells + cell] - maxLogit).toDouble())
                        denominator += probability
                        weighted += probability * k
                    }
                    dist[side] = (weighted / denominator).toFloat()
                }
                val cx = gx + 0.5f
                val cy = gy + 0.5f
                sink.add(
                    floatArrayOf(
                        (cx - dist[0]) * stride,
                        (cy - dist[1]) * stride,
                        (cx + dist[2]) * stride,
                        (cy + dist[3]) * stride,
                        personScore
                    )
                )
            }
        }
    }

    private fun updatePresence(boxes: List<FloatArray>, costMs: Long) {
        val now = System.currentTimeMillis()
        val detected = boxes.isNotEmpty()
        val immutableBoxes = boxes.map { it.copyOf() }
        personBoxes = immutableBoxes
        personCount = immutableBoxes.size
        adultLikePersonCount = immutableBoxes.count {
            val area = it[2] * it[3]
            it[3] >= ADULT_LIKE_MIN_HEIGHT && area >= ADULT_LIKE_MIN_AREA
        }

        val observation = SupervisionStateMachine.Observation(
            totalPeople = personCount,
            adultLikePeople = adultLikePersonCount
        )
        caregiverEvidenceNow = observation.caregiverEvidence
        val previousSupervision = supervision.state
        val currentSupervision = supervision.push(observation, now)
        if (recentDetections.size == ENTER_WINDOW) recentDetections.removeFirst()
        recentDetections.addLast(detected)

        if (detected) {
            val best = boxes.maxByOrNull { it[4] } ?: return
            lastSeenAt = now
            personConfidence = best[4]
            personBox = best.copyOf(4)
        }

        val wasPresent = personPresent
        if (!personPresent && recentDetections.count { it } >= ENTER_HITS) {
            personPresent = true
        } else if (personPresent && now - lastSeenAt >= LEAVE_DELAY_MS) {
            personPresent = false
            personConfidence = 0f
            personBox = FloatArray(0)
        } else if (!personPresent && !detected) {
            personConfidence = 0f
            personBox = FloatArray(0)
        }

        if (personPresent != wasPresent) {
            Log.i(
                TAG,
                    "personPresent=$personPresent count=$personCount " +
                    "adultLike=$adultLikePersonCount conf=${"%.2f".format(personConfidence)} " +
                    "supervision=$currentSupervision (${costMs}ms)"
            )
        } else if (currentSupervision != previousSupervision) {
            Log.i(
                TAG,
                "supervision=$previousSupervision->$currentSupervision count=$personCount " +
                    "adultLike=$adultLikePersonCount (${costMs}ms)"
            )
            try {
                onSupervisionChanged(previousSupervision, currentSupervision, observation)
            } catch (e: Exception) {
                Log.w(TAG, "陪护状态回调异常", e)
            }
        } else {
            Log.d(
                TAG,
                "detect=$detected count=$personCount supervision=$currentSupervision (${costMs}ms)"
            )
        }
    }

    private fun resetPresence() {
        recentDetections.clear()
        personPresent = false
        personConfidence = 0f
        personBox = FloatArray(0)
        personBoxes = emptyList()
        personCount = 0
        adultLikePersonCount = 0
        caregiverEvidenceNow = false
        lastSeenAt = 0L
        supervision.reset()
    }

    private fun nms(boxes: List<FloatArray>): List<FloatArray> {
        val sorted = boxes.sortedByDescending { it[4] }
        val kept = mutableListOf<FloatArray>()
        for (b in sorted) {
            var overlap = false
            for (k in kept) {
                val ix1 = max(b[0], k[0]); val iy1 = max(b[1], k[1])
                val ix2 = min(b[2], k[2]); val iy2 = min(b[3], k[3])
                val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
                val inter = iw * ih
                val union = (b[2] - b[0]) * (b[3] - b[1]) + (k[2] - k[0]) * (k[3] - k[1]) - inter
                if (union > 0 && inter / union > NMS_IOU) {
                    overlap = true
                    break
                }
            }
            if (!overlap) kept.add(b)
        }
        return kept
    }

    /**
     * letterbox 裁边后再去掉高度嵌套的人体框。普通 IoU 对“大框包含小框”的重复检测
     * 不敏感，直接拿框数量判断陪护时会把同一个成人误算成两个人。
     */
    private fun removeNestedPersonBoxes(boxes: List<FloatArray>): List<FloatArray> {
        val kept = mutableListOf<FloatArray>()
        for (box in boxes.sortedByDescending { it[4] }) {
            val area = box[2] * box[3]
            val nested = kept.any { other ->
                val x1 = max(box[0], other[0])
                val y1 = max(box[1], other[1])
                val x2 = min(box[0] + box[2], other[0] + other[2])
                val y2 = min(box[1] + box[3], other[1] + other[3])
                val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
                val smallerArea = min(area, other[2] * other[3])
                smallerArea > 0f && intersection / smallerArea >= 0.70f
            }
            if (!nested) kept.add(box)
        }
        return kept
    }

    private external fun nativeCreate(model: ByteArray): Long
    private external fun nativeQuery(handle: Long): IntArray
    private external fun nativeRun(handle: Long, input: ByteArray, w: Int, h: Int): Array<FloatArray>?
    private external fun nativeDestroy(handle: Long)
}
