package org.e11camera.edge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera2 视频持续送硬件编码器；YUV/JPEG 仅在快照或分析需要新帧时采集。 */
class CameraStreamer(private val context: Context) {
    companion object {
        private const val TAG = "CameraStreamer"
        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val CAPTURE_TIMEOUT_MS = 1_500L
    }

    data class JpegSnapshot(val bytes: ByteArray, val capturedAtMs: Long)

    @Volatile var previewSurface: Surface? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val callbackThread = HandlerThread("camera-cb").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val jpegExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "jpeg-worker") }
    private val desiredRunning = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val jpegMonitor = Object()
    private val demand = SnapshotDemandQueue()
    private var demandEpoch = 0L // Protected by jpegMonitor.

    @Volatile @get:Synchronized var latestJpeg: ByteArray = ByteArray(0)
        private set
    @Volatile var latestJpegCapturedAtMs: Long = 0L
        private set
    @Volatile var yuvFrameCount: Long = 0L
        private set
    @Volatile var jpegFrameCount: Long = 0L
        private set

    /** 视频活性不依赖按需 JPEG；状态查询本身不会触发新快照。 */
    val latestFrameCapturedAtMs: Long
        get() = maxOf(h264Encoder?.lastOutputAtMs ?: 0L, latestJpegCapturedAtMs)

    fun latestJpegSnapshot(): JpegSnapshot = synchronized(this) {
        JpegSnapshot(latestJpeg, latestJpegCapturedAtMs)
    }

    /** 多个消费者可共用最近的快照；只有缓存过期才进入采集队列。 */
    fun requestJpegSnapshot(maxAgeMs: Long = 750L, timeoutMs: Long = 1_500L): JpegSnapshot? {
        if (!desiredRunning.get()) return null
        val cached = latestJpegSnapshot()
        val age = System.currentTimeMillis() - cached.capturedAtMs
        if (cached.bytes.isNotEmpty() && age in 0..maxAgeMs) return cached
        return requestFreshJpeg(timeoutMs)
    }

    /** 冻结提交前的请求批次；采集开始后到来的请求必须等下一帧。 */
    fun requestFreshJpeg(timeoutMs: Long): JpegSnapshot? {
        if (timeoutMs <= 0L || !desiredRunning.get() || stopped.get() ||
            Looper.myLooper() == callbackThread.looper) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(jpegMonitor) {
            if (!desiredRunning.get()) return null
            val epoch = demandEpoch
            val sequence = demand.enqueue(deadline)
            handler.post(pumpRunnable)
            try {
                while (desiredRunning.get() && epoch == demandEpoch &&
                    !demand.isCompleted(sequence) && demand.isPending(sequence)) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) return null
                    try {
                        jpegMonitor.wait(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                if (epoch != demandEpoch || !demand.isCompleted(sequence) ||
                    SystemClock.elapsedRealtime() >= deadline) return null
                return latestJpegSnapshot().takeIf { it.bytes.isNotEmpty() }
            } finally {
                demand.cancel(sequence)
            }
        }
    }

    @Volatile private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var timestampCompositor: VideoTimestampCompositor? = null
    private var snapshotRequest: CaptureRequest? = null
    // CameraDevice, session and capture lifecycle are owned exclusively by camera-cb.
    private var cameraGeneration = 0L
    private var opening = false
    private var activeCapture: SnapshotCapture? = null
    private var unmatchedImage: Image? = null

    private class SnapshotCapture(val sequence: Long, val generation: Long, val reader: ImageReader) {
        var sensorTimestamp: Long? = null
        var processing = false
    }

    /** Called on camera-cb only after a new encoder has started successfully. */
    @Volatile var onEncoderReady: ((H264Encoder) -> Unit)? = null

    @Volatile var h264Encoder: H264Encoder? = null
        private set
    val isRunning: Boolean get() = camera != null

    fun start() {
        if (stopped.get()) return
        desiredRunning.set(true)
        handler.post { openCamera() }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!desiredRunning.get() || stopped.get() || camera != null || opening) return
        opening = true
        val generation = ++cameraGeneration
        try {
            if (h264Encoder == null) {
                val encoder = H264Encoder(WIDTH, HEIGHT)
                check(encoder.start()) { "编码器启动失败" }
                h264Encoder = encoder
                onEncoderReady?.invoke(encoder)
            }
            val camId = findFrontCamera() ?: throw IllegalStateException("找不到摄像头")
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (generation != cameraGeneration || !desiredRunning.get() || stopped.get()) {
                        c.close()
                        return
                    }
                    opening = false
                    camera = c
                    startCapture(c, generation)
                }
                override fun onDisconnected(c: CameraDevice) = cameraFailed(c, generation, "disconnected")
                override fun onError(c: CameraDevice, error: Int) = cameraFailed(c, generation, "error=$error")
            }, handler)
        } catch (e: Exception) {
            opening = false
            Log.e(TAG, "openCamera 失败", e)
            scheduleRetry()
        }
    }

    private fun cameraFailed(c: CameraDevice, generation: Long, reason: String) {
        c.close()
        if (generation != cameraGeneration) return
        Log.w(TAG, "摄像头异常 $reason")
        closeCameraOnly()
        scheduleRetry()
    }

    private val retryRunnable = Runnable { openCamera() }
    private fun scheduleRetry(delayMs: Long = 1_500L) {
        if (!desiredRunning.get() || stopped.get()) return
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun startCapture(c: CameraDevice, generation: Long) {
        try {
            val encSurface = h264Encoder?.inputSurface
                ?: throw IllegalStateException("编码器 Surface 未就绪")
            val encodedTarget = timestampCompositor?.cameraSurface ?: VideoTimestampCompositor(
                encSurface, WIDTH, HEIGHT
            ).let { compositor ->
                compositor.start()?.also { timestampCompositor = compositor } ?: encSurface
            }
            val previewTarget = previewSurface?.takeIf { it.isValid }
            val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ onImage(it) }, handler)
            imageReader = reader
            val targets = arrayListOf(encodedTarget, reader.surface)
            previewTarget?.let { targets.add(it) }
            c.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (generation != cameraGeneration || !desiredRunning.get() || camera !== c) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        fun request(includeYuv: Boolean): CaptureRequest {
                            return c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(encodedTarget)
                                previewTarget?.let { addTarget(it) }
                                if (includeYuv) addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
                            }.build()
                        }
                        snapshotRequest = request(true)
                        s.setRepeatingRequest(request(false), null, handler)
                        LedController.restoreRequestedLights()
                        Log.i(TAG, "采集已启动 ${WIDTH}x$HEIGHT，视频 30fps / YUV 按需")
                        pumpSnapshot()
                    } catch (e: Exception) {
                        Log.w(TAG, "建立采集请求失败", e)
                        restartCapture()
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (generation != cameraGeneration || camera !== c || !desiredRunning.get()) return
                    Log.e(TAG, "CaptureSession 配置失败")
                    restartCapture()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startCapture 失败", e)
            restartCapture()
        }
    }

    private val pumpRunnable = Runnable { pumpSnapshot() }
    private fun pumpSnapshot() {
        handler.removeCallbacks(pumpRunnable)
        if (!desiredRunning.get() || stopped.get() || activeCapture != null) return
        val now = SystemClock.elapsedRealtime()
        if (!synchronized(jpegMonitor) { demand.hasDemand(now) }) return
        val s = session
        val reader = imageReader
        val request = snapshotRequest
        if (s == null || reader == null || request == null) {
            handler.postDelayed(pumpRunnable, 100)
            return
        }
        val sequence = synchronized(jpegMonitor) { demand.claim(now) } ?: return
        val capture = SnapshotCapture(sequence, cameraGeneration, reader)
        activeCapture = capture
        try {
            // Also targets the encoder/preview, preserving video output for this sensor frame.
            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (activeCapture !== capture) return
                    capture.sensorTimestamp = timestamp
                    unmatchedImage?.let { pending ->
                        unmatchedImage = null
                        if (pending.timestamp == timestamp) processImage(pending, capture) else pending.close()
                    }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    if (!capture.processing) captureFailed(capture, "capture_failed=${failure.reason}")
                }
            }, handler)
            handler.postDelayed({ captureFailed(capture, "timeout") }, CAPTURE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "按需快照提交失败", e)
            captureFailed(capture, "submit_failed")
        }
    }

    private fun captureFailed(capture: SnapshotCapture, reason: String) {
        if (activeCapture !== capture) return
        Log.w(TAG, "按需快照未完成 $reason")
        unmatchedImage?.close()
        unmatchedImage = null
        activeCapture = null
        synchronized(jpegMonitor) {
            demand.release(capture.sequence)
            jpegMonitor.notifyAll()
        }
        handler.postDelayed(pumpRunnable, 100)
    }

    private fun onImage(reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        yuvFrameCount++
        val capture = activeCapture
        if (!desiredRunning.get() || imageReader !== reader || capture == null ||
            capture.reader !== reader || capture.generation != cameraGeneration || capture.processing) {
            image.close()
            return
        }
        val timestamp = capture.sensorTimestamp
        if (timestamp == null) {
            unmatchedImage?.close()
            unmatchedImage = image
        } else if (timestamp == image.timestamp) {
            processImage(image, capture)
        } else {
            image.close()
        }
    }

    private fun processImage(image: Image, capture: SnapshotCapture) {
        capture.processing = true
        val capturedAtMs = System.currentTimeMillis()
        val nv21 = try { imageToNv21(image) } catch (e: Exception) {
            Log.w(TAG, "YUV 读取失败", e)
            captureFailed(capture, "yuv_failed")
            return
        } finally {
            image.close()
        }
        jpegExecutor.execute {
            val jpeg = try {
                ByteArrayOutputStream().use { out ->
                    check(android.graphics.YuvImage(nv21, ImageFormat.NV21, WIDTH, HEIGHT, null)
                        .compressToJpeg(android.graphics.Rect(0, 0, WIDTH, HEIGHT), 30, out))
                    out.toByteArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "JPEG 转码失败", e)
                null
            }
            handler.post {
                if (activeCapture !== capture || capture.generation != cameraGeneration || !desiredRunning.get()) return@post
                if (jpeg == null) {
                    captureFailed(capture, "jpeg_failed")
                    return@post
                }
                synchronized(jpegMonitor) {
                    if (demand.complete(capture.sequence)) {
                        synchronized(this) {
                            latestJpeg = jpeg
                            latestJpegCapturedAtMs = capturedAtMs
                        }
                        jpegFrameCount++
                    }
                    jpegMonitor.notifyAll()
                }
                activeCapture = null
                pumpSnapshot()
            }
        }
    }

    /** Handle padded rows and separate/interleaved chroma planes. */
    private fun imageToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        require(w == WIDTH && h == HEIGHT)
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuffer = y.buffer.duplicate()
        val uBuffer = u.buffer.duplicate()
        val vBuffer = v.buffer.duplicate()
        val yRowStride = y.rowStride
        val yPixelStride = y.pixelStride
        val uRowStride = u.rowStride
        val uPixelStride = u.pixelStride
        val vRowStride = v.rowStride
        val vPixelStride = v.pixelStride
        for (row in 0 until h) {
            val offset = row * yRowStride
            if (yPixelStride == 1) {
                yBuffer.position(offset)
                yBuffer.get(out, row * w, w)
            } else {
                for (col in 0 until w) out[row * w + col] = yBuffer.get(offset + col * yPixelStride)
            }
        }
        var offset = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[offset++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                out[offset++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }
        return out
    }

    fun restartCapture() {
        if (!desiredRunning.get() || stopped.get()) return
        handler.post {
            if (!desiredRunning.get() || stopped.get()) return@post
            closeCameraOnly()
            scheduleRetry(300)
        }
    }

    private fun invalidateDemand() = synchronized(jpegMonitor) {
        demandEpoch++
        demand.clear()
        jpegMonitor.notifyAll()
    }

    private fun closeCameraOnly() {
        cameraGeneration++
        invalidateDemand()
        handler.removeCallbacks(pumpRunnable)
        activeCapture = null
        unmatchedImage?.close()
        unmatchedImage = null
        snapshotRequest = null
        val oldSession = session.also { session = null }
        val oldReader = imageReader.also { imageReader = null }
        val oldCamera = camera.also { camera = null }
        opening = false
        for (close in listOf<() -> Unit>({ oldSession?.close() }, { oldReader?.close() }, { oldCamera?.close() })) {
            try { close() } catch (e: Exception) { Log.w(TAG, "关闭相机会话异常", e) }
        }
    }

    private fun closeVideo() {
        timestampCompositor?.stop()
        timestampCompositor = null
        h264Encoder?.stop()
        h264Encoder = null
        synchronized(this) {
            latestJpeg = ByteArray(0)
            latestJpegCapturedAtMs = 0L
        }
    }

    fun pause() {
        desiredRunning.set(false)
        invalidateDemand()
        handler.post {
            handler.removeCallbacks(retryRunnable)
            closeCameraOnly()
            closeVideo()
            Log.i(TAG, "摄像头已暂停")
        }
    }

    fun resume() = start()

    private fun findFrontCamera(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    } ?: cameraManager.cameraIdList.firstOrNull()

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        desiredRunning.set(false)
        invalidateDemand()
        handler.post {
            handler.removeCallbacksAndMessages(null)
            closeCameraOnly()
            closeVideo()
            jpegExecutor.shutdownNow()
            callbackThread.quitSafely()
            Log.i(TAG, "摄像头已停止")
        }
    }
}
