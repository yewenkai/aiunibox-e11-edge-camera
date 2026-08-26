package org.e11camera.edge

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class CryCalibrationStatus(
    val state: String,
    val startedAt: Long,
    val calibratedAt: Long,
    val sampleCount: Int,
    val targetSamples: Int,
    val noiseRms: Float,
    val noiseScore: Float
)

/**
 * 宝宝守护：哭声检测（本地 YAMNet 分类，不联网）。
 *
 * 麦克风 16kHz 单声道连续采集，0.975s 窗口、约 0.49s 步进。安静时
 * （RMS 低于门限）主要只算能量；每隔数窗仍强制抽查一次，避免远处弱哭声
 * 被能量门限永久跳过。有声才跑 YAMNet。该模型直接输出 0..1 类别分数，
 * 取哭声相关类别的最高分作为哭声匹配分，不能再做 softmax。
 *
 * 推送策略（用户指定）：任一窗口判定为哭声立即推送，不做防抖——
 * 宁可错报不可漏报。连续哭算一个事件只推一次；安静超过 10 秒后
 * 再次哭才推下一条。画面动作检测的防抖在 MotionDetector 里，与此独立。
 *
 * 模型文件由部署者自行取得（仓库不分发权重）：filesDir/yamnet.tflite，
 * 缺失或无麦克风权限时本检测器停用，动作检测不受影响。
 */
class CryDetector(
    private val context: Context,
    private val state: DeviceStateStore,
    private val microphone: MicrophoneAudioSource,
    private val isLocalPlaybackActive: () -> Boolean,
    private val onTriggered: (Float) -> Unit
) {
    companion object {
        private const val TAG = "CryDetector"
        private const val WINDOW_SAMPLES = 15600 // 0.975s，YAMNet 输入长度
        private const val HOP_SAMPLES = WINDOW_SAMPLES / 2 // 50% 重叠，约 0.49s 步进

        /** 低于该 RMS 视为安静，跳过推理 */
        private const val SILENCE_RMS_GATE = 0.012f
        private const val SILENT_FORCE_INTERVAL_WINDOWS = 4

        // yamnet_class_map.csv: 19=Crying,sobbing 20=Baby cry,infant cry
        // 21=Whimper 22=Wail,moan
        private val CRY_LABELS = intArrayOf(19, 20, 21, 22)

        /** 灵敏度 0/1/2（低/中/高）对应的哭声分数阈值 */
        private val CRY_THRESHOLD_BY_SENSITIVITY = floatArrayOf(0.45f, 0.30f, 0.18f)

        /** 安静超过该时长后视为一个哭声事件结束，再哭推新一条 */
        private const val EPISODE_GAP_MS = 10_000L

        private const val MODEL_FILE = "yamnet.tflite"
        private const val HISTORY_LIMIT = 20
        // 50% 重叠后 30 窗仍约等于 15 秒现场底噪。
        private const val CALIBRATION_SAMPLES = 30
    }

    private val events = ArrayDeque<Long>()
    private val calibrationLock = Any()
    private val calibrationRms = mutableListOf<Float>()
    private val calibrationScores = mutableListOf<Float>()
    private val temporalPolicy = CryTemporalPolicy()

    @Volatile private var calibrationState = if (state.cryCalibratedAt > 0L) "complete" else "idle"
    @Volatile private var calibrationStartedAt = 0L

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val audioQueue = ArrayBlockingQueue<ShortArray>(100)
    @Volatile private var microphoneSubscription: MicrophoneAudioSource.Subscription? = null

    @Volatile
    private var inCryEpisode = false

    @Volatile
    private var lastCryAt = 0L

    @Volatile
    var modelLoaded: Boolean = false
        private set

    @Volatile var lastAudioRms: Float = 0f
        private set

    @Volatile var lastCryScore: Float = 0f
        private set

    @Volatile var topClassIndex: Int = -1
        private set

    @Volatile var topClassScore: Float = 0f
        private set

    @Volatile var lastAnalyzedAt: Long = 0L
        private set

    val currentThreshold: Float
        get() {
            val base = CRY_THRESHOLD_BY_SENSITIVITY[state.babySensitivity.coerceIn(0, 2)]
            val calibrated = if (state.cryCalibratedAt > 0L) state.cryNoiseScore + 0.08f else 0f
            return maxOf(base, calibrated).coerceAtMost(0.85f)
        }

    val silenceRmsGate: Float
        get() = if (state.cryCalibratedAt > 0L) {
            maxOf(SILENCE_RMS_GATE, state.cryNoiseRms * 1.8f).coerceAtMost(0.06f)
        } else {
            SILENCE_RMS_GATE
        }

    val isActive: Boolean
        get() = inCryEpisode

    fun start() {
        if (running) return
        if (!microphone.hasPermission) {
            Log.w(TAG, "缺少 RECORD_AUDIO 权限，哭声检测停用")
            return
        }
        val model = loadModel() ?: run {
            Log.w(TAG, "未找到 $MODEL_FILE（filesDir），哭声检测停用")
            return
        }
        temporalPolicy.reset()
        audioQueue.clear()
        running = true
        thread = Thread { loop(model) }.apply { start() }
    }

    fun stop() {
        running = false
        cancelCalibration()
        microphoneSubscription?.close()
        microphoneSubscription = null
        audioQueue.clear()
        thread?.interrupt()
        thread = null
        inCryEpisode = false
        lastCryAt = 0L
        temporalPolicy.reset()
    }

    fun lastEventAt(): Long? = synchronized(events) { events.lastOrNull() }

    fun recentEvents(): List<Long> = synchronized(events) { events.toList() }

    /** 成人离开时只重评估仍有新哭声证据的事件，避免沿用即将结束的旧 episode。 */
    fun hasRecentEvidence(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 2_500L): Boolean =
        inCryEpisode && lastCryAt > 0L && now - lastCryAt in 0..maxAgeMs

    fun startAmbientCalibration(): Boolean {
        if (!running) return false
        synchronized(calibrationLock) {
            calibrationRms.clear()
            calibrationScores.clear()
            calibrationStartedAt = System.currentTimeMillis()
            calibrationState = "running"
        }
        inCryEpisode = false
        lastCryAt = 0L
        temporalPolicy.reset()
        Log.i(TAG, "开始环境底噪校准，仅保存统计量")
        return true
    }

    fun cancelCalibration() {
        synchronized(calibrationLock) {
            calibrationRms.clear()
            calibrationScores.clear()
            calibrationStartedAt = 0L
            calibrationState = if (state.cryCalibratedAt > 0L) "complete" else "idle"
        }
    }

    fun resetCalibration() {
        cancelCalibration()
        state.cryNoiseRms = 0f
        state.cryNoiseScore = 0f
        state.cryCalibratedAt = 0L
        calibrationState = "idle"
    }

    fun calibrationStatus(): CryCalibrationStatus = synchronized(calibrationLock) {
        CryCalibrationStatus(
            state = calibrationState,
            startedAt = calibrationStartedAt,
            calibratedAt = state.cryCalibratedAt,
            sampleCount = calibrationRms.size.coerceAtMost(CALIBRATION_SAMPLES),
            targetSamples = CALIBRATION_SAMPLES,
            noiseRms = state.cryNoiseRms,
            noiseScore = state.cryNoiseScore
        )
    }

    private fun isCalibrating(): Boolean = calibrationState == "running"

    private fun collectCalibration(rms: Float, cryScore: Float) {
        var completedRms: List<Float>? = null
        var completedScores: List<Float>? = null
        synchronized(calibrationLock) {
            if (calibrationState != "running") return
            calibrationRms += rms
            calibrationScores += cryScore
            if (calibrationRms.size >= CALIBRATION_SAMPLES) {
                completedRms = calibrationRms.toList()
                completedScores = calibrationScores.toList()
                calibrationState = "saving"
            }
        }
        val rmsSamples = completedRms ?: return
        val scoreSamples = completedScores ?: return
        try {
            state.cryNoiseRms = SceneCalibrationPolicy.percentile(rmsSamples, 0.90f)
            state.cryNoiseScore = SceneCalibrationPolicy.percentile(scoreSamples, 0.95f)
            state.cryCalibratedAt = System.currentTimeMillis()
            synchronized(calibrationLock) {
                calibrationState = "complete"
                calibrationStartedAt = 0L
            }
            Log.i(
                TAG,
                "底噪校准完成 rms=${"%.4f".format(state.cryNoiseRms)} " +
                    "cry=${"%.3f".format(state.cryNoiseScore)} threshold=${"%.2f".format(currentThreshold)}"
            )
        } catch (e: Exception) {
            calibrationState = "failed"
            Log.w(TAG, "保存底噪校准失败", e)
        }
    }

    private fun loadModel(): MappedByteBuffer? {
        val f = File(context.filesDir, MODEL_FILE)
        if (!f.exists()) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
            }
        } catch (e: Exception) {
            Log.w(TAG, "模型加载失败: ${e.message}")
            null
        }
    }

    private fun loop(model: MappedByteBuffer) {
        var interpreter: Interpreter? = null
        val assembler = PcmWindowAssembler(WINDOW_SAMPLES, HOP_SAMPLES)
        val input = Array(1) { FloatArray(WINDOW_SAMPLES) }
        val inputs = arrayOf<Any>(input)
        val output = Array(1) { FloatArray(521) }
        val outputs = mapOf<Int, Any>(0 to output)
        var skippedSilentWindows = 0
        try {
            interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(1) })
            modelLoaded = true
            microphoneSubscription = microphone.subscribe("cry-detector") { samples ->
                if (!running) return@subscribe
                if (!audioQueue.offer(samples)) {
                    audioQueue.poll()
                    audioQueue.offer(samples)
                }
            }
            if (microphoneSubscription == null) {
                Log.w(TAG, "共享麦克风初始化失败，哭声检测停用")
                return
            }
            Log.i(TAG, "哭声检测已启动（复用共享麦克风，0.975s 窗口/0.49s 步进）")
            while (running && !Thread.currentThread().isInterrupted) {
                val samples = audioQueue.poll(1, TimeUnit.SECONDS) ?: continue
                if (isLocalPlaybackActive()) {
                    assembler.reset()
                    skippedSilentWindows = 0
                    lastCryScore = 0f
                    topClassIndex = -1
                    topClassScore = 0f
                    lastAnalyzedAt = System.currentTimeMillis()
                    temporalPolicy.reset()
                    handleWindow(isCry = false, cry = 0f)
                    continue
                }
                assembler.push(samples) { window ->
                    skippedSilentWindows = analyzeWindow(
                        window = window,
                        interpreter = interpreter,
                        input = input,
                        inputs = inputs,
                        output = output,
                        outputs = outputs,
                        skippedSilentWindows = skippedSilentWindows
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "哭声检测异常退出: ${e.message}", e)
        } finally {
            modelLoaded = false
            microphoneSubscription?.close()
            microphoneSubscription = null
            audioQueue.clear()
            interpreter?.close()
            Log.i(TAG, "哭声检测已停止")
        }
    }

    private fun analyzeWindow(
        window: FloatArray,
        interpreter: Interpreter,
        input: Array<FloatArray>,
        inputs: Array<Any>,
        output: Array<FloatArray>,
        outputs: Map<Int, Any>,
        skippedSilentWindows: Int
    ): Int {
        var sum = 0.0
        for (value in window) sum += value.toDouble() * value
        val rms = sqrt(sum / WINDOW_SAMPLES).toFloat()
        lastAudioRms = rms
        lastAnalyzedAt = System.currentTimeMillis()
        val calibrating = isCalibrating()
        val threshold = currentThreshold
        val forceSilentCheck = skippedSilentWindows >= SILENT_FORCE_INTERVAL_WINDOWS - 1
        if (rms < silenceRmsGate && !calibrating && !forceSilentCheck) {
            lastCryScore = 0f
            topClassIndex = -1
            topClassScore = 0f
            handleWindow(temporalPolicy.push(0f, threshold), cry = 0f)
            return skippedSilentWindows + 1
        }

        System.arraycopy(window, 0, input[0], 0, window.size)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
        // 模型元数据明确说明 scores 已在 0..1 范围；再次 softmax 会把
        // 521 类压到约 1/521，导致哭声分数恒定在约 0.01。
        val scores = output[0]
        var cry = 0f
        for (index in CRY_LABELS) cry = maxOf(cry, scores[index])
        var topIndex = 0
        for (index in scores.indices) if (scores[index] > scores[topIndex]) topIndex = index
        lastCryScore = cry
        topClassIndex = topIndex
        topClassScore = scores[topIndex]
        Log.i(
            TAG,
            "音频窗口 rms=${"%.3f".format(rms)} cry=${"%.3f".format(cry)} " +
                "threshold=${"%.2f".format(threshold)} top=$topIndex(${"%.3f".format(scores[topIndex])})"
        )

        if (calibrating) {
            collectCalibration(rms, cry)
            temporalPolicy.reset()
            handleWindow(isCry = false, cry = 0f)
        } else {
            handleWindow(temporalPolicy.push(cry, threshold), cry)
        }
        return 0
    }

    /** 事件逻辑：哭声窗口立即触发推送；安静超过 EPISODE_GAP 后事件结束。 */
    private fun handleWindow(isCry: Boolean, cry: Float) {
        val now = System.currentTimeMillis()
        if (isCry) {
            lastCryAt = now
            if (!inCryEpisode) {
                inCryEpisode = true
                synchronized(events) {
                    events.addLast(now)
                    while (events.size > HISTORY_LIMIT) events.removeFirst()
                }
                Log.i(TAG, "检测到哭声，立即触发（score=${"%.2f".format(cry)}）")
                try {
                    onTriggered(cry)
                } catch (e: Exception) {
                    Log.w(TAG, "触发回调异常", e)
                }
            }
        } else if (inCryEpisode && now - lastCryAt > EPISODE_GAP_MS) {
            inCryEpisode = false
            temporalPolicy.reset()
            Log.i(TAG, "哭声事件结束")
        }
    }

}
