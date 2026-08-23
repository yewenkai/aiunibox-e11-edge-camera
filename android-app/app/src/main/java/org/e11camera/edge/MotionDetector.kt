package org.e11camera.edge

import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宝宝守护：基于帧差的动作检测。
 *
 * 复用 CameraStreamer 每秒产出的 JPEG 快照（latestJpeg）做降采样帧差，
 * 不触碰 30fps 零拷贝编码路径。宝宝翻身/醒来是持续数秒的动作，
 * 1 秒采样粒度足够。
 *
 * 判定：滑动窗口内"有动静"的秒数达到阈值即触发；连续安静一段时间后回落。
 * 云台转动、切换场景/IR-CUT/补光灯等全局亮度变化通过丢弃参考帧排除。
 */
class MotionDetector(
    private val streamer: CameraStreamer,
    private val state: DeviceStateStore,
    private val motor: MotorController,
    private val onTriggered: () -> Unit = {}
) {
    companion object {
        private const val TAG = "MotionDetector"
        private const val SAMPLE_INTERVAL_SEC = 1L
        private val FULL_ROI = floatArrayOf(0f, 0f, 1f, 1f)

        /** ROI 每边最少像素数（80×60 网格），过小视为全画面 */
        private const val MIN_ROI_PIXELS = 6

        /** 单像素灰度差超过该值才计入变化像素（快照 JPEG 质量 30，噪声偏大） */
        private const val PIXEL_DIFF_THRESHOLD = 26

        /** 灵敏度 0/1/2（低/中/高）对应的变化像素占比阈值 */
        private val RATIO_THRESHOLDS = doubleArrayOf(0.12, 0.07, 0.03)

        private const val HISTORY_LIMIT = 20
    }

    @Volatile
    private var scheduler = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    private var reference: IntArray? = null

    private val trigger = TriggerStateMachine()
    private val events = ArrayDeque<Long>()

    val isActive: Boolean
        get() = trigger.isActive

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleAtFixedRate({ tick() }, 2, SAMPLE_INTERVAL_SEC, TimeUnit.SECONDS)
        Log.i(TAG, "动作检测已启动")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdownNow()
        reference = null
        trigger.reset()
        Log.i(TAG, "动作检测已停止")
    }

    /** 外部场景即将/已经突变（云台、IR-CUT、补光灯等），丢弃参考帧防误报。 */
    fun notifyReferenceInvalid() {
        reference = null
    }

    fun lastEventAt(): Long? = synchronized(events) { events.lastOrNull() }

    fun recentEvents(): List<Long> = synchronized(events) { events.toList() }

    private fun tick() {
        try {
            if (motor.isBusy()) {
                // 云台转动整幅画面都在变：丢弃参考帧并清空窗口，不产生样本。
                reference = null
                trigger.reset()
                return
            }
            val jpeg = streamer.latestJpeg
            if (jpeg.isEmpty()) {
                reference = null
                return
            }
            val gray = decodeGray(jpeg)
            if (gray == null) {
                reference = null
                return
            }
            val ref = reference
            if (ref == null) {
                reference = gray
                return
            }
            val ratio = changedRatio(ref, gray, decodeRoi())
            reference = gray
            val threshold = RATIO_THRESHOLDS[state.babySensitivity.coerceIn(0, 2)]
            if (trigger.push(ratio >= threshold)) {
                val now = System.currentTimeMillis()
                synchronized(events) {
                    events.addLast(now)
                    while (events.size > HISTORY_LIMIT) events.removeFirst()
                }
                // 触发瞬间请求 I 帧，让此刻进来的观看者立刻出画面。
                streamer.h264Encoder?.requestKeyFrame()
                try {
                    onTriggered()
                } catch (e: Exception) {
                    Log.w(TAG, "触发回调异常", e)
                }
                Log.i(TAG, "检测到持续动作，已触发")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tick 异常", e)
        }
    }

    /** JPEG → 灰度数组。640×480 快照用 inSampleSize=8 直接解码到 80×60。 */
    private fun decodeGray(jpeg: ByteArray): IntArray? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) {
            bmp.recycle()
            return null
        }
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            IntArray(w * h) { i ->
                val c = px[i]
                ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
        } finally {
            bmp.recycle()
        }
    }

    /** 解析归一化 ROI "x,y,w,h"，非法或过小时回退全画面。 */
    private fun decodeRoi(): FloatArray {
        val parts = state.motionRoi.split(",")
        if (parts.size != 4) return FULL_ROI
        val v = parts.map { it.trim().toFloatOrNull() ?: return FULL_ROI }
        if (v.any { it < 0f || it > 1f } || v[2] <= 0f || v[3] <= 0f) return FULL_ROI
        return floatArrayOf(v[0], v[1], v[2], v[3])
    }

    /** 均值归一化帧差：先各自减去均值再比较，排除全局亮度变化。
     *  只在 ROI 区域内统计（均值同样只算区域），区域过小回退全画面。 */
    private fun changedRatio(a: IntArray, b: IntArray, roi: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        // decodeGray 固定把 640×480 快照降到 80×60
        val w = 80
        val h = a.size / w
        var x0 = (roi[0] * w).toInt().coerceIn(0, w - 1)
        var y0 = (roi[1] * h).toInt().coerceIn(0, h - 1)
        var x1 = Math.ceil(((roi[0] + roi[2]) * w).toDouble()).toInt().coerceIn(x0 + 1, w)
        var y1 = Math.ceil(((roi[1] + roi[3]) * h).toDouble()).toInt().coerceIn(y0 + 1, h)
        if (x1 - x0 < MIN_ROI_PIXELS || y1 - y0 < MIN_ROI_PIXELS) {
            x0 = 0; y0 = 0; x1 = w; y1 = h
        }
        var sumA = 0L
        var sumB = 0L
        var count = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                sumA += a[row + x]
                sumB += b[row + x]
                count++
            }
        }
        if (count == 0) return 0.0
        val meanA = sumA / count
        val meanB = sumB / count
        var changed = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val d = Math.abs((a[row + x] - meanA) - (b[row + x] - meanB))
                if (d > PIXEL_DIFF_THRESHOLD) changed++
            }
        }
        return changed.toDouble() / count
    }
}
