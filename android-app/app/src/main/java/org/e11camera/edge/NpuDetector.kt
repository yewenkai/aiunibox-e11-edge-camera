package org.e11camera.edge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val notificationsSuppressed: Boolean,
    val queueMs: Long = 0L,
    val captureMs: Long = 0L,
    val totalWaitMs: Long = 0L,
    val sourceAgeMs: Long = -1L,
    val degradedReason: String = "",
    val sourceCapturedElapsedMs: Long = 0L,
    val sourceMaxAgeMs: Long = AlertVisionRefreshPolicy.MAX_SOURCE_AGE_MS
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
        val snapshot: AlertVisionSnapshot,
        val source: CameraStreamer.JpegSnapshot? = null,
        val sourceMaxAgeMs: Long = AlertVisionRefreshPolicy.MAX_SOURCE_AGE_MS
    )

    /** 每次 start 的独立代次；旧任务只释放自己这一代的在途标记。 */
    private class SnapshotWorker(
        val executor: ExecutorService,
        val scheduler: ScheduledExecutorService,
        val inFlight: AtomicBoolean = AtomicBoolean(false)
    )

    companion object {
        private const val TAG = "NpuDetector"
        private const val MODEL_FILE = "yolov8n_rk3566.rknn"
        private const val SAMPLE_INTERVAL_SEC = 2L
        private const val DEFAULT_ALERT_TIMEOUT_MS = AlertVisionRefreshPolicy.DEFAULT_TIMEOUT_MS
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
    private val pendingAlertRefreshes = AtomicInteger(0)
    private val inferenceLock = Any()
    private val recentDetections = ArrayDeque<Boolean>(ENTER_WINDOW)
    private val supervision = SupervisionStateMachine()
    private val calibrationLock = Any()
    private val adultCalibrationBoxes = mutableListOf<Pair<Float, Float>>()
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var snapshotWorker: SnapshotWorker? = null
    private data class PublishedAnalysis(
        val snapshot: AlertVisionSnapshot,
        val sourceElapsedMs: Long
    )
    @Volatile private var publishedAnalysis: PublishedAnalysis? = null

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
        get() = currentSnapshot("current_state").notificationsSuppressed

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

    @Volatile var lastAlertRefreshQueueMs: Long = 0L
        private set
    @Volatile var lastAlertRefreshCaptureMs: Long = 0L
        private set
    @Volatile var lastAlertRefreshTotalMs: Long = 0L
        private set
    @Volatile var lastAlertSourceAgeMs: Long = -1L
        private set
    @Volatile var lastAlertDegradedReason: String = ""
        private set

    @Synchronized
    fun start() {
        if (running.get()) return
        if (!loadNativeAndModel()) return
        val executor = Executors.newSingleThreadScheduledExecutor()
        scheduler = executor
        val worker = SnapshotWorker(
            Executors.newSingleThreadExecutor { task -> Thread(task, "npu-snapshot") },
            executor
        )
        snapshotWorker = worker
        running.set(true)
        // 守护启动会主动预热；常规轮询稍后开始，避免与 YAMNet 初始化抢 CPU。
        executor.scheduleAtFixedRate({ tick(worker) }, 5, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "NPU 检测已启动（YOLOv8n @${inputW}×${inputH}，每 ${SAMPLE_INTERVAL_SEC}s 一帧）")
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.getAndSet(false)
        val worker = snapshotWorker
        snapshotWorker = null
        worker?.executor?.shutdownNow()
        if (!wasRunning && handle == 0L) return
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

    private fun isSnapshotWorkerCurrent(worker: SnapshotWorker): Boolean =
        running.get() && snapshotWorker === worker && scheduler === worker.scheduler

    private fun tick(worker: SnapshotWorker) {
        // 取帧不占用告警调度线程；最多一个常规采样在取帧或等待推理。
        if (!isSnapshotWorkerCurrent(worker) || pendingAlertRefreshes.get() > 0 ||
            !worker.inFlight.compareAndSet(false, true)) return
        try {
            worker.executor.execute snapshot@{
                var handedOff = false
                try {
                    if (!isSnapshotWorkerCurrent(worker) || pendingAlertRefreshes.get() > 0) return@snapshot
                    val snapshot = streamer.requestJpegSnapshot() ?: return@snapshot
                    if (!isSnapshotWorkerCurrent(worker) || pendingAlertRefreshes.get() > 0 ||
                        snapshot.bytes.isEmpty()) return@snapshot
                    worker.scheduler.execute inference@{
                        try {
                            synchronized(inferenceLock) {
                                if (!isSnapshotWorkerCurrent(worker) || pendingAlertRefreshes.get() > 0) return@inference
                                runInference(snapshot, "scheduled", worker)
                            }
                        } catch (e: Exception) {
                            if (e is InterruptedException) Thread.currentThread().interrupt()
                            Log.w(TAG, "常规 NPU 分析异常", e)
                        } finally {
                            worker.inFlight.set(false)
                        }
                    }
                    handedOff = true
                } catch (_: RejectedExecutionException) {
                    // stop 可以在取帧完成到提交推理之间关闭这一代调度器。
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    Log.w(TAG, "请求 NPU 快照异常", e)
                } finally {
                    if (!handedOff) worker.inFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            worker.inFlight.set(false)
        } catch (e: Exception) {
            worker.inFlight.set(false)
            Log.w(TAG, "tick 异常", e)
        }
    }

    /**
     * 告警排队、取帧和推理共用一个单调时钟截止时间。取帧动态预留推理时间；
     * 超时后已开始的 native 推理可结束，但过期排队任务不再取帧或启动新推理。
     */
    fun refreshForAlert(
        timeoutMs: Long = DEFAULT_ALERT_TIMEOUT_MS,
        forceFresh: Boolean = false
    ): AlertVisionSnapshot {
        val requestedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val deadline = startedElapsed + timeoutMs.coerceAtLeast(1L)
        val worker = snapshotWorker
        if (worker == null || !isSnapshotWorkerCurrent(worker) || !modelLoaded) {
            return recordAlertRefresh(currentSnapshot("unavailable"), requestedAt, startedElapsed, worker)
        }
        val recent = currentSnapshot("recent_frame", AlertVisionRefreshPolicy.RECENT_SOURCE_REUSE_MS)
        if (!forceFresh && recent.analyzedAt > 0L && recent.degradedReason.isEmpty()) {
            return recordAlertRefresh(recent, requestedAt, startedElapsed, worker)
        }
        val cancelled = AtomicBoolean(false)
        val taskStarted = AtomicBoolean(false)
        val queueMs = java.util.concurrent.atomic.AtomicLong(0L)
        val captureMs = java.util.concurrent.atomic.AtomicLong(0L)
        fun fallback(status: String, reason: String = status): AlertVisionSnapshot =
            currentSnapshot(status, AlertVisionRefreshPolicy.RECENT_SOURCE_REUSE_MS).copy(
                queueMs = if (taskStarted.get()) queueMs.get() else SystemClock.elapsedRealtime() - startedElapsed,
                captureMs = captureMs.get(), degradedReason = reason
            )
        fun canContinue(): Boolean = !cancelled.get() && isSnapshotWorkerCurrent(worker) &&
            AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime()) > 0L

        pendingAlertRefreshes.incrementAndGet()
        return try {
            try {
                val future = worker.scheduler.submit<AlertVisionSnapshot> {
                    queueMs.set(SystemClock.elapsedRealtime() - startedElapsed)
                    taskStarted.set(true)
                    if (!canContinue()) return@submit fallback("timeout", "queue_deadline")
                    val remaining = AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
                    val captureBudget = AlertVisionRefreshPolicy.captureWaitMs(remaining, lastInferenceMs)
                    val captureStarted = SystemClock.elapsedRealtime()
                    val fresh = if (captureBudget > 0L) streamer.requestFreshJpeg(captureBudget) else null
                    captureMs.set(SystemClock.elapsedRealtime() - captureStarted)
                    if (!canContinue()) return@submit fallback("timeout", "capture_deadline")
                    val source = fresh ?: streamer.latestJpegSnapshot()
                    if (source.bytes.isEmpty()) return@submit fallback("no_frame", "no_frame")
                    val refreshStatus = if (fresh != null) "refreshed" else "latest_frame"
                    val sourceLimit = AlertVisionRefreshPolicy.maxAgeForAlert(refreshStatus)
                    val sourceAge = sourceAgeMs(source)
                    if (!AlertVisionRefreshPolicy.sourceUsable(sourceAge, sourceLimit)) {
                        return@submit fallback("stale_frame", "source_expired")
                    }
                    val result = executeInference(
                        source,
                        refreshStatus,
                        publishPresence = true,
                        worker = worker,
                        canContinue = ::canContinue
                    )
                    if (!result.available) return@submit fallback("unavailable", "inference_unavailable")
                    result.snapshot.copy(
                        queueMs = queueMs.get(),
                        captureMs = captureMs.get(),
                        degradedReason = if (fresh == null) "fresh_capture_timeout_used_cache" else result.snapshot.degradedReason
                    )
                }
                try {
                    val remaining = AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
                    if (remaining <= 0L) throw TimeoutException()
                    recordAlertRefresh(future.get(remaining, TimeUnit.MILLISECONDS), requestedAt, startedElapsed, worker)
                } catch (_: TimeoutException) {
                    cancelled.set(true)
                    future.cancel(false)
                    recordAlertRefresh(fallback("timeout", "total_deadline"), requestedAt, startedElapsed, worker)
                } catch (e: InterruptedException) {
                    cancelled.set(true)
                    future.cancel(false)
                    Thread.currentThread().interrupt()
                    recordAlertRefresh(fallback("interrupted"), requestedAt, startedElapsed, worker)
                }
            } catch (_: RejectedExecutionException) {
                recordAlertRefresh(fallback("unavailable"), requestedAt, startedElapsed, worker)
            } catch (e: Exception) {
                cancelled.set(true)
                Log.w(TAG, "告警前 NPU 补跑失败", e)
                recordAlertRefresh(fallback("failed"), requestedAt, startedElapsed, worker)
            }
        } finally {
            pendingAlertRefreshes.decrementAndGet()
        }
    }

    /**
     * 守护启动时先完成一次可能较慢的首轮推理，避免第一个真实
     * 告警把 RKNN/Bitmap 预热时间算进 600ms 等待上限。
     */
    fun warmUpForGuard(timeoutMs: Long = 8_000L): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "拒绝在主线程等待 NPU 预热")
            return false
        }
        val worker = snapshotWorker
        if (worker == null || !isSnapshotWorkerCurrent(worker) || !modelLoaded) return false
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        val cancelled = AtomicBoolean(false)
        fun canContinue(): Boolean = !cancelled.get() && isSnapshotWorkerCurrent(worker) &&
            AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime()) > 0L
        return try {
            val future = worker.scheduler.submit<Boolean> {
                if (!canContinue()) return@submit false
                // 开机时 HTTP 服务可能早于 Camera HAL 就绪，给首帧恢复留足时间。
                val captureBudget = minOf(5_000L, AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime()))
                val fresh = streamer.requestFreshJpeg(captureBudget)
                val source = fresh ?: streamer.latestJpegSnapshot()
                val sourceLimit = AlertVisionRefreshPolicy.maxAgeForAlert(if (fresh != null) "refreshed" else "latest_frame")
                if (source.bytes.isEmpty() || !canContinue()) false
                else {
                    var passes = 0
                    var stable = false
                    var finalPass: InferencePass? = null
                    while (passes < 4 && !stable) {
                        // 多轮可复用同一画面做性能预热，但不能把同一帧重复
                        // 计入陪护防抖，否则一次成人命中会被误当成连续多帧证据。
                        val result = executeInference(
                            source, "warmup", publishPresence = false, worker = worker,
                            canContinue = ::canContinue, maxSourceAgeMs = sourceLimit
                        )
                        if (!result.available) return@submit false
                        finalPass = result
                        passes++
                        // 至少两轮；某些 RK3566 运行时需要第三轮才进入稳定时延。
                        stable = passes >= 2 && result.inferenceMs in 1L..250L
                    }
                    val published = canContinue() && finalPass?.let { publishWarmupPresence(it, worker) } == true
                    Log.i(TAG, "NPU 预热完成 passes=$passes stable=$stable last=${lastInferenceMs}ms")
                    published
                }
            }
            try {
                val remaining = AlertVisionRefreshPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
                if (remaining <= 0L) throw TimeoutException()
                future.get(remaining, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                cancelled.set(true)
                future.cancel(false)
                false
            } catch (_: InterruptedException) {
                cancelled.set(true)
                future.cancel(false)
                Thread.currentThread().interrupt()
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
        refreshStatus: String,
        worker: SnapshotWorker
    ): AlertVisionSnapshot = executeInference(source, refreshStatus, true, worker).snapshot

    private fun sourceAgeMs(source: CameraStreamer.JpegSnapshot): Long =
        AlertVisionRefreshPolicy.sourceAgeMs(
            System.currentTimeMillis(), source.capturedAtMs,
            SystemClock.elapsedRealtime(), source.capturedElapsedMs
        )

    private fun executeInference(
        source: CameraStreamer.JpegSnapshot,
        refreshStatus: String,
        publishPresence: Boolean,
        worker: SnapshotWorker,
        canContinue: () -> Boolean = { isSnapshotWorkerCurrent(worker) },
        maxSourceAgeMs: Long = AlertVisionRefreshPolicy.maxAgeForAlert(refreshStatus)
    ): InferencePass = synchronized(inferenceLock) {
        fun unavailable(status: String) = InferencePass(
            available = false, boxes = emptyList(), inferenceMs = 0L,
            snapshot = currentSnapshot(status)
        )
        if (!isSnapshotWorkerCurrent(worker) || handle == 0L || !canContinue()) {
            return@synchronized unavailable("unavailable")
        }
        if (!AlertVisionRefreshPolicy.sourceUsable(sourceAgeMs(source), maxSourceAgeMs)) {
            return@synchronized unavailable("stale_frame")
        }
        val t0 = SystemClock.elapsedRealtime()
        val boxes = detectPersons(source.bytes, canContinue)
            ?: return@synchronized unavailable("unavailable")
        val cost = SystemClock.elapsedRealtime() - t0
        // stop/start 或采集迟滞期间返回的旧推理不能给新一代发布陪护证据。
        if (!isSnapshotWorkerCurrent(worker) || !AlertVisionRefreshPolicy.sourceUsable(sourceAgeMs(source), maxSourceAgeMs)) {
            return@synchronized unavailable("stale_frame")
        }
        lastSourceCapturedAt = source.capturedAtMs
        lastInferenceMs = cost
        lastAnalyzedAt = System.currentTimeMillis()
        if (publishPresence) {
            updatePresence(boxes, cost) { publishAnalysis(source, refreshStatus, maxSourceAgeMs) }
        }
        InferencePass(
            available = true, boxes = boxes, inferenceMs = cost,
            snapshot = currentSnapshot(refreshStatus), source = source, sourceMaxAgeMs = maxSourceAgeMs
        )
    }

    private fun publishAnalysis(source: CameraStreamer.JpegSnapshot, refreshStatus: String, maxSourceAgeMs: Long) {
        publishedAnalysis = PublishedAnalysis(
            AlertVisionSnapshot(
                refreshStatus = refreshStatus,
                analyzedAt = lastAnalyzedAt,
                sourceCapturedAt = source.capturedAtMs,
                inferenceMs = lastInferenceMs,
                personCount = personCount,
                adultLikePersonCount = adultLikePersonCount,
                supervisionState = supervisionState,
                notificationsSuppressed = caregiverEvidenceNow || supervision.notificationsSuppressed,
                sourceCapturedElapsedMs = source.capturedElapsedMs,
                sourceMaxAgeMs = maxSourceAgeMs
            ),
            sourceElapsedMs = source.capturedElapsedMs
        )
    }

    private fun publishWarmupPresence(result: InferencePass, worker: SnapshotWorker): Boolean = synchronized(inferenceLock) {
        val source = result.source ?: return@synchronized false
        if (!isSnapshotWorkerCurrent(worker) || handle == 0L ||
            !AlertVisionRefreshPolicy.sourceUsable(sourceAgeMs(source), result.sourceMaxAgeMs)) return@synchronized false
        updatePresence(result.boxes, result.inferenceMs) { publishAnalysis(source, "warmup", result.sourceMaxAgeMs) }
        true
    }

    private fun currentSnapshot(
        refreshStatus: String,
        maxSourceAgeMs: Long = AlertVisionRefreshPolicy.MAX_SOURCE_AGE_MS
    ): AlertVisionSnapshot {
        val published = publishedAnalysis
        if (published == null) {
            return AlertVisionSnapshot(
                refreshStatus, 0L, 0L, 0L, 0, 0,
                SupervisionStateMachine.State.UNKNOWN, false, degradedReason = "no_analysis"
            )
        }
        val snapshot = published.snapshot
        val age = AlertVisionRefreshPolicy.sourceAgeMs(
            System.currentTimeMillis(), snapshot.sourceCapturedAt,
            SystemClock.elapsedRealtime(), published.sourceElapsedMs
        )
        return AlertVisionRefreshPolicy.safeDecision(
            snapshot.copy(refreshStatus = refreshStatus), age, running.get(), maxSourceAgeMs
        )
    }

    /** 复制同一份完整分析；源过期时返回 UNKNOWN，而不为历史成人结果重新贴当前时间。 */
    fun currentDecisionSnapshot(refreshStatus: String = "current_state"): AlertVisionSnapshot =
        currentSnapshot(refreshStatus)

    private fun recordAlertRefresh(
        snapshot: AlertVisionSnapshot,
        requestedAt: Long,
        startedElapsed: Long,
        expectedWorker: SnapshotWorker?
    ): AlertVisionSnapshot {
        val maxSourceAge = AlertVisionRefreshPolicy.maxAgeForAlert(snapshot.refreshStatus)
        // 调用线程醒来时再次检查，不把 Future 完成之后的等待漏出证据时效限制。
        val result = AlertVisionRefreshPolicy.safeDecision(
            snapshot,
            AlertVisionRefreshPolicy.sourceAgeMs(
                System.currentTimeMillis(), snapshot.sourceCapturedAt,
                SystemClock.elapsedRealtime(), snapshot.sourceCapturedElapsedMs
            ),
            expectedWorker != null && isSnapshotWorkerCurrent(expectedWorker), maxSourceAge
        ).copy(totalWaitMs = SystemClock.elapsedRealtime() - startedElapsed)
        lastAlertRefreshStatus = result.refreshStatus
        lastAlertRefreshAt = requestedAt
        lastAlertRefreshQueueMs = result.queueMs
        lastAlertRefreshCaptureMs = result.captureMs
        lastAlertRefreshTotalMs = result.totalWaitMs
        lastAlertSourceAgeMs = result.sourceAgeMs
        lastAlertDegradedReason = result.degradedReason
        return result
    }

    /** JPEG → letterbox 640 → NPU 推理 → yolov8 解码 → NMS → person 框（归一化 x,y,w,h,conf）。 */
    private fun detectPersons(jpeg: ByteArray, canContinue: () -> Boolean): List<FloatArray>? {
        if (!canContinue()) return null
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
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
            // 与姿态模型共用 NPU 时，等锁耗时也算入同一 deadline；超时不启动 nativeRun。
            if (!canContinue()) return@synchronized null
            nativeRun(handle, rgb, inputW, inputH)
        } ?: return null
        if (outputs.size != outputCount || outputs.size !in setOf(6, 9)) return null
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

    private fun updatePresence(boxes: List<FloatArray>, costMs: Long, publish: () -> Unit) {
        val now = System.currentTimeMillis()
        val detected = boxes.isNotEmpty()
        val immutableBoxes = boxes.map { it.copyOf() }
        personBoxes = immutableBoxes
        personCount = immutableBoxes.size
        adultLikePersonCount = immutableBoxes.count {
            AdultEvidencePolicy.isAdultLike(
                box = it,
                minHeight = deviceState.adultLikeMinHeight,
                minArea = deviceState.adultLikeMinArea
            )
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
        // 在状态回调之前发布原子快照，回调不会混用上一帧人数和本帧时间。
        publish()
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
        lastAlertRefreshQueueMs = 0L
        lastAlertRefreshCaptureMs = 0L
        lastAlertRefreshTotalMs = 0L
        lastAlertSourceAgeMs = -1L
        lastAlertDegradedReason = ""
        publishedAnalysis = null
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
