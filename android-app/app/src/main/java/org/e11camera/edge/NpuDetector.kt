package org.e11camera.edge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class AlertVisionSnapshot(
    val refreshStatus: String,
    val analyzedAt: Long,
    val sourceCapturedAt: Long,
    val inferenceMs: Long,
    val personCount: Int,
    val adultLikePersonCount: Int,
    val supervisionState: SupervisionStateMachine.State,
    val notificationsSuppressed: Boolean
)

data class AdultCalibrationStatus(
    val state: String,
    val startedAt: Long,
    val calibratedAt: Long,
    val sampleCount: Int,
    val targetSamples: Int,
    val currentPersonCount: Int,
    val medianHeight: Float,
    val medianArea: Float,
    val minHeight: Float,
    val minArea: Float
)

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
    private val deviceState: DeviceStateStore,
    private val onSupervisionChanged: (
        previous: SupervisionStateMachine.State,
        current: SupervisionStateMachine.State,
        observation: SupervisionStateMachine.Observation
    ) -> Unit = { _, _, _ -> }
) {
    private data class InferencePass(
        val available: Boolean,
        val boxes: List<FloatArray>,
        val inferenceMs: Long,
        val snapshot: AlertVisionSnapshot
    )

    companion object {
        private const val TAG = "NpuDetector"
        private const val MODEL_FILE = "yolov8n_rk3566.rknn"
        private const val SAMPLE_INTERVAL_SEC = 2L
        private const val ALERT_FRESH_FRAME_TIMEOUT_MS = 100L
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

        private const val ADULT_CALIBRATION_SAMPLES = 8
    }

    private val running = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val recentDetections = ArrayDeque<Boolean>(ENTER_WINDOW)
    private val supervision = SupervisionStateMachine()
    private val calibrationLock = Any()
    private val adultCalibrationBoxes = mutableListOf<Pair<Float, Float>>()
    private var scheduler: ScheduledExecutorService? = null

    @Volatile private var adultCalibrationState =
        if (deviceState.adultCalibratedAt > 0L) "complete" else "idle"
    @Volatile private var adultCalibrationStartedAt = 0L
    @Volatile private var adultCalibrationPersonCount = 0

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

    @Volatile var lastAnalyzedAt: Long = 0L
        private set

    @Volatile var lastSourceCapturedAt: Long = 0L
        private set

    @Volatile var lastInferenceMs: Long = 0L
        private set

    @Volatile var lastAlertRefreshStatus: String = "never"
        private set

    @Volatile var lastAlertRefreshAt: Long = 0L
        private set

    @Synchronized
    fun start() {
        if (running.get()) return
        if (!loadNativeAndModel()) return
        val executor = Executors.newSingleThreadScheduledExecutor()
        scheduler = executor
        running.set(true)
        // 守护启动会主动预热；常规轮询稍后开始，避免与 YAMNet 初始化抢 CPU。
        executor.scheduleAtFixedRate({ tick() }, 5, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "NPU 检测已启动（YOLOv8n @${inputW}×${inputH}，每 ${SAMPLE_INTERVAL_SEC}s 一帧）")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && handle == 0L) return
        cancelAdultCalibration()
        scheduler?.shutdownNow()
        scheduler = null
        synchronized(inferenceLock) {
            destroyModel()
            resetPresence()
        }
        Log.i(TAG, "NPU 检测已停止")
    }

    fun startAdultCalibration(): Boolean {
        if (!running.get() || !modelLoaded) return false
        synchronized(calibrationLock) {
            adultCalibrationBoxes.clear()
            adultCalibrationStartedAt = System.currentTimeMillis()
            adultCalibrationPersonCount = 0
            adultCalibrationState = "running"
        }
        Log.i(TAG, "开始成人常见站位校准，仅保存体型比例统计")
        return true
    }

    fun cancelAdultCalibration() {
        synchronized(calibrationLock) {
            adultCalibrationBoxes.clear()
            adultCalibrationStartedAt = 0L
            adultCalibrationPersonCount = 0
            adultCalibrationState = if (deviceState.adultCalibratedAt > 0L) "complete" else "idle"
        }
    }

    fun resetAdultCalibration() {
        cancelAdultCalibration()
        deviceState.adultLikeMinHeight = 0.48f
        deviceState.adultLikeMinArea = 0.14f
        deviceState.adultMedianHeight = 0f
        deviceState.adultMedianArea = 0f
        deviceState.adultCalibratedAt = 0L
        adultCalibrationState = "idle"
    }

    fun adultCalibrationStatus(): AdultCalibrationStatus = synchronized(calibrationLock) {
        AdultCalibrationStatus(
            state = adultCalibrationState,
            startedAt = adultCalibrationStartedAt,
            calibratedAt = deviceState.adultCalibratedAt,
            sampleCount = adultCalibrationBoxes.size.coerceAtMost(ADULT_CALIBRATION_SAMPLES),
            targetSamples = ADULT_CALIBRATION_SAMPLES,
            currentPersonCount = adultCalibrationPersonCount,
            medianHeight = deviceState.adultMedianHeight,
            medianArea = deviceState.adultMedianArea,
            minHeight = deviceState.adultLikeMinHeight,
            minArea = deviceState.adultLikeMinArea
        )
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
        handle = synchronized(NpuExecutionGate.lock) { nativeCreate(modelFile.readBytes()) }
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
            synchronized(NpuExecutionGate.lock) { nativeDestroy(handle) }
            handle = 0
        }
        modelLoaded = false
        outputCount = 0
    }

    private fun tick() {
        if (!running.get()) return
        try {
            val snapshot = streamer.latestJpegSnapshot()
            if (snapshot.bytes.isEmpty()) return
            runInference(snapshot, "scheduled")
        } catch (e: Exception) {
            Log.w(TAG, "tick 异常", e)
        }
    }

    /**
     * 告警前尝试强制取下一帧并补跑 NPU。调用方最多等待 [timeoutMs]，
     * 超时后按安全侧继续事件流程，已开始的推理可以在后台完成并更新状态。
     */
    fun refreshForAlert(timeoutMs: Long = 300L): AlertVisionSnapshot {
        val requestedAt = System.currentTimeMillis()
        val executor = scheduler
        if (!running.get() || !modelLoaded || executor == null) {
            return recordAlertRefresh(currentSnapshot("unavailable"), requestedAt)
        }
        return try {
            val future = executor.submit<AlertVisionSnapshot> {
                val fresh = streamer.requestFreshJpeg(ALERT_FRESH_FRAME_TIMEOUT_MS)
                val source = fresh ?: streamer.latestJpegSnapshot()
                if (source.bytes.isEmpty()) {
                    currentSnapshot("no_frame")
                } else {
                    runInference(source, if (fresh != null) "refreshed" else "latest_frame")
                }
            }
            try {
                recordAlertRefresh(
                    future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS),
                    requestedAt
                )
            } catch (_: TimeoutException) {
                future.cancel(false)
                recordAlertRefresh(currentSnapshot("timeout"), requestedAt)
            }
        } catch (_: RejectedExecutionException) {
            recordAlertRefresh(currentSnapshot("unavailable"), requestedAt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            recordAlertRefresh(currentSnapshot("interrupted"), requestedAt)
        } catch (e: Exception) {
            Log.w(TAG, "告警前 NPU 补跑失败", e)
            recordAlertRefresh(currentSnapshot("failed"), requestedAt)
        }
    }

    /**
     * 守护启动时先完成一次可能较慢的首轮推理，避免第一个真实
     * 告警把 RKNN/Bitmap 预热时间算进 300ms 等待上限。
     */
    fun warmUpForGuard(timeoutMs: Long = 8_000L): Boolean {
        val executor = scheduler
        if (!running.get() || !modelLoaded || executor == null) return false
        return try {
            val future = executor.submit<Boolean> {
                // 开机时 HTTP 服务可能早于 Camera HAL 就绪，给首帧恢复留足时间。
                val fresh = streamer.requestFreshJpeg(5_000L)
                val source = fresh ?: streamer.latestJpegSnapshot()
                if (source.bytes.isEmpty()) false
                else {
                    var passes = 0
                    var stable = false
                    var finalPass: InferencePass? = null
                    while (passes < 4 && !stable) {
                        // 多轮可复用同一画面做性能预热，但不能把同一帧重复
                        // 计入陪护防抖，否则一次成人命中会被误当成连续多帧证据。
                        val result = executeInference(source, "warmup", publishPresence = false)
                        if (!result.available) return@submit false
                        finalPass = result
                        passes++
                        // 至少两轮；某些 RK3566 运行时需要第三轮才进入稳定时延。
                        stable = passes >= 2 && result.inferenceMs in 1L..250L
                    }
                    val published = finalPass?.let { publishWarmupPresence(it) } == true
                    Log.i(TAG, "NPU 预热完成 passes=$passes stable=$stable last=${lastInferenceMs}ms")
                    published
                }
            }
            try {
                future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(false)
                false
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "NPU 预热失败，守护将按降级方式继续", e)
            false
        }
    }

    private fun runInference(
        source: CameraStreamer.JpegSnapshot,
        refreshStatus: String
    ): AlertVisionSnapshot = executeInference(source, refreshStatus, publishPresence = true).snapshot

    private fun executeInference(
        source: CameraStreamer.JpegSnapshot,
        refreshStatus: String,
        publishPresence: Boolean
    ): InferencePass = synchronized(inferenceLock) {
        if (!running.get() || handle == 0L) {
            return@synchronized InferencePass(
                available = false,
                boxes = emptyList(),
                inferenceMs = 0L,
                snapshot = currentSnapshot("unavailable")
            )
        }
        val t0 = System.currentTimeMillis()
        val boxes = detectPersons(source.bytes)
        val cost = System.currentTimeMillis() - t0
        lastSourceCapturedAt = source.capturedAtMs
        lastInferenceMs = cost
        lastAnalyzedAt = System.currentTimeMillis()
        if (publishPresence) updatePresence(boxes, cost)
        InferencePass(
            available = true,
            boxes = boxes,
            inferenceMs = cost,
            snapshot = currentSnapshot(refreshStatus)
        )
    }

    private fun publishWarmupPresence(result: InferencePass): Boolean = synchronized(inferenceLock) {
        if (!running.get() || handle == 0L) return@synchronized false
        updatePresence(result.boxes, result.inferenceMs)
        true
    }

    private fun currentSnapshot(refreshStatus: String): AlertVisionSnapshot = AlertVisionSnapshot(
        refreshStatus = refreshStatus,
        analyzedAt = lastAnalyzedAt,
        sourceCapturedAt = lastSourceCapturedAt,
        inferenceMs = lastInferenceMs,
        personCount = personCount,
        adultLikePersonCount = adultLikePersonCount,
        supervisionState = supervisionState,
        notificationsSuppressed = notificationsSuppressed
    )

    private fun recordAlertRefresh(
        snapshot: AlertVisionSnapshot,
        requestedAt: Long
    ): AlertVisionSnapshot {
        lastAlertRefreshStatus = snapshot.refreshStatus
        lastAlertRefreshAt = requestedAt
        return snapshot
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

        val outputs = synchronized(NpuExecutionGate.lock) {
            nativeRun(handle, rgb, inputW, inputH)
        } ?: return emptyList()
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
            it[3] >= deviceState.adultLikeMinHeight && area >= deviceState.adultLikeMinArea
        }
        collectAdultCalibration(immutableBoxes)

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
        } else {
            Log.d(
                TAG,
                "detect=$detected count=$personCount supervision=$currentSupervision (${costMs}ms)"
            )
        }
        if (currentSupervision != previousSupervision) {
            try {
                onSupervisionChanged(previousSupervision, currentSupervision, observation)
            } catch (e: Exception) {
                Log.w(TAG, "陪护状态回调异常", e)
            }
        }
    }

    private fun collectAdultCalibration(boxes: List<FloatArray>) {
        if (adultCalibrationState != "running") return
        var completed: List<Pair<Float, Float>>? = null
        synchronized(calibrationLock) {
            if (adultCalibrationState != "running") return
            adultCalibrationPersonCount = boxes.size
            if (boxes.size != 1) return
            val box = boxes.single()
            adultCalibrationBoxes += box[3] to (box[2] * box[3])
            if (adultCalibrationBoxes.size >= ADULT_CALIBRATION_SAMPLES) {
                completed = adultCalibrationBoxes.toList()
                adultCalibrationState = "saving"
            }
        }
        val samples = completed ?: return
        try {
            val medianHeight = SceneCalibrationPolicy.median(samples.map { it.first })
            val medianArea = SceneCalibrationPolicy.median(samples.map { it.second })
            val thresholds = SceneCalibrationPolicy.adultThresholds(medianHeight, medianArea)
            // 低于成人站位中位值留出坐姿和距离波动余量。
            deviceState.adultMedianHeight = medianHeight
            deviceState.adultMedianArea = medianArea
            deviceState.adultLikeMinHeight = thresholds.minHeight
            deviceState.adultLikeMinArea = thresholds.minArea
            deviceState.adultCalibratedAt = System.currentTimeMillis()
            synchronized(calibrationLock) {
                adultCalibrationState = "complete"
                adultCalibrationStartedAt = 0L
            }
            Log.i(
                TAG,
                "成人体型校准完成 median=${"%.2f".format(medianHeight)}/" +
                    "${"%.2f".format(medianArea)} threshold=" +
                    "${"%.2f".format(deviceState.adultLikeMinHeight)}/" +
                    "${"%.2f".format(deviceState.adultLikeMinArea)}"
            )
        } catch (e: Exception) {
            adultCalibrationState = "failed"
            Log.w(TAG, "保存成人体型校准失败", e)
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
        lastAnalyzedAt = 0L
        lastSourceCapturedAt = 0L
        lastInferenceMs = 0L
        lastAlertRefreshStatus = "never"
        lastAlertRefreshAt = 0L
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
