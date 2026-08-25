package org.e11camera.edge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * YOLOv8-Pose 影子分析：默认不通知，只记录姿态、关键点有效率与耗时。
 * 调试骨架只把归一化关键点交给浏览器 canvas，绝不修改 H.264/JPEG 原始画面。
 */
class PoseDetector(
    private val context: Context,
    private val streamer: CameraStreamer,
    private val deviceState: DeviceStateStore,
    private val diagnostics: PoseDiagnosticsStore,
    private val onPostureChanged: (PostureTransition, PoseAnalysisSnapshot) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "PoseDetector"
        private const val POSE_MODEL_FILE = "yolov8n_pose_rk3566.rknn"
        private const val FACE_MODEL_FILE = "retinaface_mobile320_rk3566.rknn"
        private const val SAMPLE_INTERVAL_SEC = 2L
        private const val DEFAULT_POSE_INPUT = 640
        private const val DEFAULT_FACE_INPUT = 320
        private const val POSE_OUTPUT_COUNT = 4
        private const val FACE_OUTPUT_COUNT = 3
        private const val POSE_CHANNELS = 65
        private const val DFL_BINS = 16
        private const val TOTAL_KEYPOINTS = 17
        private const val PERSON_THRESHOLD = 0.35f
        private const val NMS_IOU = 0.45f
        private const val FACE_THRESHOLD = 0.50f
        private const val TARGET_MISS_LIMIT = 3
    }

    private data class PoseCandidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val keypointIndex: Int
    )

    private data class PreparedInput(
        val rgb: ByteArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class FacePass(
        val status: FaceVisibilityStatus,
        val score: Float = 0f,
        val inferenceMs: Long = 0L
    )

    private data class AnalysisPass(
        val snapshot: PoseAnalysisSnapshot,
        val transition: PostureTransition?
    )

    private val running = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val stateMachine = PostureStateMachine()
    private var scheduler: ScheduledExecutorService? = null

    private var poseHandle = 0L
    private var poseInputW = DEFAULT_POSE_INPUT
    private var poseInputH = DEFAULT_POSE_INPUT
    private var faceHandle = 0L
    private var faceInputW = DEFAULT_FACE_INPUT
    private var faceInputH = DEFAULT_FACE_INPUT
    private var lastTargetBox = FloatArray(0)
    private var targetMisses = 0

    @Volatile var modelLoaded: Boolean = false
        private set

    @Volatile var faceModelLoaded: Boolean = false
        private set

    @Volatile var latest: PoseAnalysisSnapshot = PoseAnalysisSnapshot()
        private set

    val poseModelAvailable: Boolean
        get() = File(context.filesDir, POSE_MODEL_FILE).isFile

    val faceModelAvailable: Boolean
        get() = File(context.filesDir, FACE_MODEL_FILE).isFile

    @Synchronized
    fun start() {
        if (running.get() || !deviceState.poseAnalysisEnabled) return
        if (!loadNativeAndPoseModel()) return
        val executor = Executors.newSingleThreadScheduledExecutor()
        scheduler = executor
        running.set(true)
        // 与每 2 秒的人体检测错开，避免同一时刻争用单核 NPU。
        executor.scheduleAtFixedRate({ tick() }, 1, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "Pose 影子分析已启动（${poseInputW}×${poseInputH}，每 ${SAMPLE_INTERVAL_SEC}s 一帧）")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && poseHandle == 0L && faceHandle == 0L) return
        scheduler?.shutdownNow()
        scheduler = null
        synchronized(inferenceLock) {
            destroyModelsLocked()
            stateMachine.reset()
            resetTargetTracking()
            latest = latest.copy(
                rawPosture = PostureLabel.UNKNOWN,
                stablePosture = PostureLabel.UNKNOWN,
                personCount = 0,
                adultLikePersonCount = 0,
                targetBox = FloatArray(0),
                keypoints = FloatArray(0),
                faceStatus = FaceVisibilityStatus.IDLE,
                faceScore = 0f,
                faceInferenceMs = 0L
            )
        }
        Log.i(TAG, "Pose 影子分析已停止并释放模型")
    }

    /** 配置变化后由服务调用；面部开关关闭时立即释放按需模型。 */
    fun applyConfiguration(visualGuardianRunning: Boolean) {
        if (visualGuardianRunning && deviceState.poseAnalysisEnabled) start() else stop()
        if (!deviceState.faceVisibilityEnabled) releaseFaceModel()
    }

    fun recordFeedback(value: String): Boolean = diagnostics.recordFeedback(value, latest)

    fun diagnosticsSummary(): PoseDiagnosticsSummary = diagnostics.summary()

    /** 单次模型冒烟，不启动动作/哭声、不调用姿态通知回调，也不计入 24 小时影子样本。 */
    @Synchronized
    fun selfTest(): PoseAnalysisSnapshot? {
        val wasRunning = running.get()
        if (!modelLoaded && !loadNativeAndPoseModel()) return null
        val source = streamer.latestJpegSnapshot()
        if (source.bytes.isEmpty()) {
            if (!wasRunning) synchronized(inferenceLock) { destroyModelsLocked() }
            return null
        }
        var result = analyze(source, recordDiagnostics = false).snapshot
        // 冒烟时额外用画面中心裁剪检查 RetinaFace 的加载与输出契约；结果不作业务判断。
        if (faceModelAvailable) {
            val bitmap = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
            if (bitmap != null) {
                try {
                    val keypoints = FloatArray(TOTAL_KEYPOINTS * 3).apply {
                        this[0] = 0.5f
                        this[1] = 0.5f
                        this[2] = 1f
                    }
                    val face = synchronized(inferenceLock) {
                        analyzeFaceOnDemand(
                            bitmap,
                            PosePerson(
                                box = floatArrayOf(0.25f, 0.25f, 0.5f, 0.5f, 1f),
                                keypoints = keypoints
                            )
                        )
                    }
                    result = result.copy(
                        faceStatus = face.status,
                        faceScore = face.score,
                        faceInferenceMs = face.inferenceMs
                    )
                    latest = result
                } finally {
                    bitmap.recycle()
                }
            }
        }
        if (!wasRunning) {
            synchronized(inferenceLock) {
                destroyModelsLocked()
                stateMachine.reset()
                resetTargetTracking()
            }
        }
        return result
    }

    private fun tick() {
        if (!running.get() || !deviceState.poseAnalysisEnabled) return
        try {
            val source = streamer.latestJpegSnapshot()
            if (source.bytes.isEmpty()) return
            val result = analyze(source, recordDiagnostics = true)
            val callbackTransition = result.transition ?: result.snapshot.stablePosture
                .takeIf {
                    deviceState.poseNotificationsEnabled &&
                        PostureClassificationPolicy.isRisk(it, deviceState.poseSideRiskEnabled)
                }
                ?.let { PostureTransition(it, it) }
            callbackTransition?.let { transition ->
                try {
                    onPostureChanged(transition, result.snapshot)
                } catch (e: Exception) {
                    Log.w(TAG, "姿态状态回调异常", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pose 分析异常", e)
        }
    }

    private fun analyze(
        source: CameraStreamer.JpegSnapshot,
        recordDiagnostics: Boolean
    ): AnalysisPass = synchronized(inferenceLock) {
        if (poseHandle == 0L) {
            return@synchronized AnalysisPass(latest, null)
        }
        val bitmap = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
            ?: return@synchronized AnalysisPass(latest, null)
        try {
            val poseStartedAt = System.currentTimeMillis()
            val prepared = preparePoseInput(bitmap)
            val outputs = synchronized(NpuExecutionGate.lock) {
                nativeRun(poseHandle, prepared.rgb, poseInputW, poseInputH)
            }
            val persons = if (outputs?.size == POSE_OUTPUT_COUNT) {
                decodePose(outputs, prepared)
            } else {
                emptyList()
            }
            val poseInferenceMs = System.currentTimeMillis() - poseStartedAt
            val target = selectTarget(persons)
            val classification = PostureClassificationPolicy.classify(target)
            val face = if (
                target != null &&
                PostureClassificationPolicy.isRisk(
                    classification.label,
                    deviceState.poseSideRiskEnabled
                ) &&
                deviceState.faceVisibilityEnabled
            ) {
                analyzeFaceOnDemand(bitmap, target)
            } else if (deviceState.faceVisibilityEnabled) {
                FacePass(FaceVisibilityStatus.IDLE)
            } else {
                FacePass(FaceVisibilityStatus.DISABLED)
            }

            val transition = stateMachine.push(classification.label)
            val now = System.currentTimeMillis()
            val snapshot = PoseAnalysisSnapshot(
                analyzedAt = now,
                sourceCapturedAt = source.capturedAtMs,
                poseInferenceMs = poseInferenceMs,
                rawPosture = classification.label,
                stablePosture = stateMachine.state,
                keypointValidity = classification.keypointValidity,
                faceKeypointValidity = classification.faceKeypointValidity,
                personCount = persons.size,
                adultLikePersonCount = persons.count(::isAdultLike),
                targetBox = target?.box?.copyOf() ?: FloatArray(0),
                keypoints = target?.keypoints?.copyOf() ?: FloatArray(0),
                faceStatus = face.status,
                faceScore = face.score,
                faceInferenceMs = face.inferenceMs
            )
            latest = snapshot
            if (recordDiagnostics) diagnostics.record(snapshot)
            Log.d(
                TAG,
                "raw=${snapshot.rawPosture.apiValue} stable=${snapshot.stablePosture.apiValue} " +
                    "people=${snapshot.personCount} valid=${"%.2f".format(snapshot.keypointValidity)} " +
                    "pose=${snapshot.poseInferenceMs}ms face=${snapshot.faceStatus.apiValue}/${snapshot.faceInferenceMs}ms"
            )
            AnalysisPass(snapshot, transition)
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadNativeAndPoseModel(): Boolean {
        try {
            System.loadLibrary("rknnrt")
            System.loadLibrary("e11npu")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "native 库加载失败，Pose 停用: ${e.message}")
            return false
        }
        val modelFile = File(context.filesDir, POSE_MODEL_FILE)
        if (!modelFile.isFile) {
            Log.w(TAG, "未找到 $POSE_MODEL_FILE，Pose 停用")
            return false
        }
        poseHandle = synchronized(NpuExecutionGate.lock) { nativeCreate(modelFile.readBytes()) }
        if (poseHandle == 0L) return false
        val query = nativeQuery(poseHandle)
        if (query.size < 6 || query[0] != 1 || query[1] != 1 || query[2] != POSE_OUTPUT_COUNT) {
            Log.w(TAG, "Pose 模型契约不匹配: ${query.joinToString()}")
            destroyPoseModelLocked()
            return false
        }
        poseInputW = query[3]
        poseInputH = query[4]
        modelLoaded = true
        return true
    }

    private fun loadFaceModelLocked(): Boolean {
        if (faceHandle != 0L && faceModelLoaded) return true
        val modelFile = File(context.filesDir, FACE_MODEL_FILE)
        if (!modelFile.isFile) return false
        faceHandle = synchronized(NpuExecutionGate.lock) { nativeCreate(modelFile.readBytes()) }
        if (faceHandle == 0L) return false
        val query = nativeQuery(faceHandle)
        if (query.size < 6 || query[0] != 1 || query[1] != 1 || query[2] != FACE_OUTPUT_COUNT) {
            Log.w(TAG, "面部可见性模型契约不匹配: ${query.joinToString()}")
            destroyFaceModelLocked()
            return false
        }
        faceInputW = query[3]
        faceInputH = query[4]
        faceModelLoaded = true
        Log.i(TAG, "风险候选出现，已按需加载面部可见性模型")
        return true
    }

    private fun releaseFaceModel() = synchronized(inferenceLock) {
        destroyFaceModelLocked()
    }

    private fun destroyModelsLocked() {
        destroyFaceModelLocked()
        destroyPoseModelLocked()
    }

    private fun destroyPoseModelLocked() {
        if (poseHandle != 0L) {
            synchronized(NpuExecutionGate.lock) { nativeDestroy(poseHandle) }
            poseHandle = 0L
        }
        modelLoaded = false
    }

    private fun destroyFaceModelLocked() {
        if (faceHandle != 0L) {
            synchronized(NpuExecutionGate.lock) { nativeDestroy(faceHandle) }
            faceHandle = 0L
        }
        faceModelLoaded = false
    }

    private fun preparePoseInput(bitmap: Bitmap): PreparedInput {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val scale = min(poseInputW.toFloat() / sourceWidth, poseInputH.toFloat() / sourceHeight)
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        val padX = (poseInputW - drawWidth) / 2f
        val padY = (poseInputH - drawHeight) / 2f
        val canvasBitmap = Bitmap.createBitmap(poseInputW, poseInputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        // Rockchip YOLOv8-Pose 示例使用 56 灰度 letterbox。
        canvas.drawColor(0xFF383838.toInt())
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(padX, padY, padX + drawWidth, padY + drawHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        val rgb = bitmapToBytes(canvasBitmap, bgr = false)
        canvasBitmap.recycle()
        return PreparedInput(rgb, sourceWidth, sourceHeight, scale, padX, padY)
    }

    private fun decodePose(outputs: Array<FloatArray>, input: PreparedInput): List<PosePerson> {
        val branchOutputs = outputs.mapNotNull { output ->
            if (output.size % POSE_CHANNELS != 0) return@mapNotNull null
            val cells = output.size / POSE_CHANNELS
            val side = sqrt(cells.toDouble()).toInt()
            if (side * side != cells || side !in setOf(20, 40, 80)) null
            else Triple(output, side, cells)
        }.sortedByDescending { it.third }
        if (branchOutputs.size != 3) return emptyList()
        val totalCells = branchOutputs.sumOf { it.third }
        val keypointOutput = outputs.firstOrNull { it.size == TOTAL_KEYPOINTS * 3 * totalCells }
            ?: return emptyList()

        val candidates = mutableListOf<PoseCandidate>()
        var keypointOffset = 0
        for ((branch, grid, cells) in branchOutputs) {
            val stride = poseInputW / grid
            decodePoseBranch(branch, grid, stride, keypointOffset, candidates)
            keypointOffset += cells
        }
        val kept = poseNms(candidates)
        return removeNestedPosePersons(kept.mapNotNull { candidate ->
            val x1 = ((candidate.x1 - input.padX) / input.scale / input.sourceWidth).coerceIn(0f, 1f)
            val y1 = ((candidate.y1 - input.padY) / input.scale / input.sourceHeight).coerceIn(0f, 1f)
            val x2 = ((candidate.x2 - input.padX) / input.scale / input.sourceWidth).coerceIn(0f, 1f)
            val y2 = ((candidate.y2 - input.padY) / input.scale / input.sourceHeight).coerceIn(0f, 1f)
            if (x2 - x1 <= 0.01f || y2 - y1 <= 0.01f) return@mapNotNull null
            val keypoints = FloatArray(TOTAL_KEYPOINTS * 3)
            for (index in 0 until TOTAL_KEYPOINTS) {
                val sourceBase = index * 3 * totalCells
                val targetBase = index * 3
                val modelX = keypointOutput[sourceBase + candidate.keypointIndex]
                val modelY = keypointOutput[sourceBase + totalCells + candidate.keypointIndex]
                val confidence = keypointOutput[sourceBase + totalCells * 2 + candidate.keypointIndex]
                keypoints[targetBase] = ((modelX - input.padX) / input.scale / input.sourceWidth).coerceIn(0f, 1f)
                keypoints[targetBase + 1] = ((modelY - input.padY) / input.scale / input.sourceHeight).coerceIn(0f, 1f)
                keypoints[targetBase + 2] = confidence.coerceIn(0f, 1f)
            }
            PosePerson(
                box = floatArrayOf(x1, y1, x2 - x1, y2 - y1, candidate.score),
                keypoints = keypoints
            )
        })
    }

    private fun decodePoseBranch(
        output: FloatArray,
        grid: Int,
        stride: Int,
        keypointOffset: Int,
        sink: MutableList<PoseCandidate>
    ) {
        val cells = grid * grid
        if (output.size != POSE_CHANNELS * cells) return
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val cell = gy * grid + gx
                val score = sigmoid(output[64 * cells + cell])
                if (score < PERSON_THRESHOLD) continue
                val distances = FloatArray(4)
                for (side in 0 until 4) {
                    val channel = side * DFL_BINS
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (bin in 0 until DFL_BINS) {
                        maxLogit = max(maxLogit, output[(channel + bin) * cells + cell])
                    }
                    var denominator = 0.0
                    var weighted = 0.0
                    for (bin in 0 until DFL_BINS) {
                        val probability = exp((output[(channel + bin) * cells + cell] - maxLogit).toDouble())
                        denominator += probability
                        weighted += probability * bin
                    }
                    distances[side] = if (denominator > 0.0) (weighted / denominator).toFloat() else 0f
                }
                val centerX = gx + 0.5f
                val centerY = gy + 0.5f
                sink += PoseCandidate(
                    x1 = (centerX - distances[0]) * stride,
                    y1 = (centerY - distances[1]) * stride,
                    x2 = (centerX + distances[2]) * stride,
                    y2 = (centerY + distances[3]) * stride,
                    score = score,
                    keypointIndex = keypointOffset + cell
                )
            }
        }
    }

    private fun poseNms(candidates: List<PoseCandidate>): List<PoseCandidate> {
        val kept = mutableListOf<PoseCandidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (kept.none { iou(candidate, it) > NMS_IOU }) kept += candidate
        }
        return kept
    }

    private fun iou(a: PoseCandidate, b: PoseCandidate): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val intersection = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) +
            (b.x2 - b.x1) * (b.y2 - b.y1) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun removeNestedPosePersons(persons: List<PosePerson>): List<PosePerson> {
        val kept = mutableListOf<PosePerson>()
        for (person in persons.sortedByDescending { it.box[4] }) {
            val box = person.box
            val area = box[2] * box[3]
            val nested = kept.any { other ->
                val otherBox = other.box
                val x1 = max(box[0], otherBox[0])
                val y1 = max(box[1], otherBox[1])
                val x2 = min(box[0] + box[2], otherBox[0] + otherBox[2])
                val y2 = min(box[1] + box[3], otherBox[1] + otherBox[3])
                val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
                val smaller = min(area, otherBox[2] * otherBox[3])
                smaller > 0f && intersection / smaller >= 0.70f
            }
            if (!nested) kept += person
        }
        return kept
    }

    private fun selectTarget(persons: List<PosePerson>): PosePerson? {
        val selected = PoseTargetPolicy.select(persons, lastTargetBox, ::isAdultLike)
        if (selected != null) {
            lastTargetBox = selected.box.copyOf(4)
            targetMisses = 0
        } else {
            targetMisses++
            if (targetMisses >= TARGET_MISS_LIMIT) resetTargetTracking()
        }
        return selected
    }

    private fun isAdultLike(person: PosePerson): Boolean {
        return AdultEvidencePolicy.isAdultLike(
            box = person.box,
            minHeight = deviceState.adultLikeMinHeight,
            minArea = deviceState.adultLikeMinArea
        )
    }

    private fun resetTargetTracking() {
        lastTargetBox = FloatArray(0)
        targetMisses = 0
    }

    private fun analyzeFaceOnDemand(bitmap: Bitmap, person: PosePerson): FacePass {
        val startedAt = System.currentTimeMillis()
        if (!loadFaceModelLocked()) {
            return FacePass(
                FaceVisibilityStatus.MODEL_UNAVAILABLE,
                inferenceMs = System.currentTimeMillis() - startedAt
            )
        }
        val crop = estimateHeadCrop(person) ?: return FacePass(FaceVisibilityStatus.NO_HEAD_CROP)
        val faceBitmap = Bitmap.createBitmap(faceInputW, faceInputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(faceBitmap)
        val sourceRect = Rect(
            (crop.left * bitmap.width).toInt(),
            (crop.top * bitmap.height).toInt(),
            (crop.right * bitmap.width).toInt(),
            (crop.bottom * bitmap.height).toInt()
        )
        canvas.drawBitmap(
            bitmap,
            sourceRect,
            RectF(0f, 0f, faceInputW.toFloat(), faceInputH.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        // 官方 RetinaFace_mobile320 转换链以 OpenCV BGR + mean[104,117,123] 为输入。
        val bgr = bitmapToBytes(faceBitmap, bgr = true)
        faceBitmap.recycle()
        val outputs = synchronized(NpuExecutionGate.lock) {
            nativeRun(faceHandle, bgr, faceInputW, faceInputH)
        }
        val confidence = outputs
            ?.firstOrNull { it.size == 4_200 * 2 }
            ?.let(::maximumFaceConfidence)
            ?: 0f
        val cost = System.currentTimeMillis() - startedAt
        return FacePass(
            status = if (confidence >= FACE_THRESHOLD) {
                FaceVisibilityStatus.VISIBLE
            } else {
                FaceVisibilityStatus.NOT_VISIBLE
            },
            score = confidence,
            inferenceMs = cost
        )
    }

    private fun estimateHeadCrop(person: PosePerson): RectF? {
        val points = person.keypoints
        val facePoints = (0..4).filter { keypointValid(points, it) }
        var centerX: Float
        var centerY: Float
        var cropSize: Float
        if (facePoints.isNotEmpty()) {
            centerX = facePoints.map { points[it * 3] }.average().toFloat()
            centerY = facePoints.map { points[it * 3 + 1] }.average().toFloat()
            cropSize = max(person.box[2], person.box[3]) * 0.32f
        } else if (
            keypointValid(points, 5) && keypointValid(points, 6) &&
            keypointValid(points, 11) && keypointValid(points, 12)
        ) {
            val shoulderX = (points[5 * 3] + points[6 * 3]) / 2f
            val shoulderY = (points[5 * 3 + 1] + points[6 * 3 + 1]) / 2f
            val hipX = (points[11 * 3] + points[12 * 3]) / 2f
            val hipY = (points[11 * 3 + 1] + points[12 * 3 + 1]) / 2f
            centerX = shoulderX + (shoulderX - hipX) * 0.48f
            centerY = shoulderY + (shoulderY - hipY) * 0.48f
            val dx = shoulderX - hipX
            val dy = shoulderY - hipY
            cropSize = sqrt(dx * dx + dy * dy) * 0.95f
        } else {
            return null
        }
        cropSize = cropSize.coerceIn(0.08f, 0.42f)
        centerX = centerX.coerceIn(0f, 1f)
        centerY = centerY.coerceIn(0f, 1f)
        val half = cropSize / 2f
        val left = (centerX - half).coerceIn(0f, 1f)
        val top = (centerY - half).coerceIn(0f, 1f)
        val right = (centerX + half).coerceIn(0f, 1f)
        val bottom = (centerY + half).coerceIn(0f, 1f)
        if (right - left < 0.04f || bottom - top < 0.04f) return null
        return RectF(left, top, right, bottom)
    }

    private fun keypointValid(points: FloatArray, index: Int): Boolean {
        if (points.size < (index + 1) * 3) return false
        val offset = index * 3
        return points[offset + 2] >= PostureClassificationPolicy.KEYPOINT_CONFIDENCE &&
            points[offset] in 0f..1f && points[offset + 1] in 0f..1f
    }

    private fun maximumFaceConfidence(confidences: FloatArray): Float {
        var maximum = 0f
        var index = 1
        while (index < confidences.size) {
            maximum = max(maximum, confidences[index])
            index += 2
        }
        return maximum.coerceIn(0f, 1f)
    }

    private fun bitmapToBytes(bitmap: Bitmap, bgr: Boolean): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 3)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = (color shr 16 and 0xFF).toByte()
            val green = (color shr 8 and 0xFF).toByte()
            val blue = (color and 0xFF).toByte()
            if (bgr) {
                bytes[index * 3] = blue
                bytes[index * 3 + 1] = green
                bytes[index * 3 + 2] = red
            } else {
                bytes[index * 3] = red
                bytes[index * 3 + 1] = green
                bytes[index * 3 + 2] = blue
            }
        }
        return bytes
    }

    private fun sigmoid(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    private external fun nativeCreate(model: ByteArray): Long
    private external fun nativeQuery(handle: Long): IntArray
    private external fun nativeRun(handle: Long, input: ByteArray, w: Int, h: Int): Array<FloatArray>?
    private external fun nativeDestroy(handle: Long)
}
