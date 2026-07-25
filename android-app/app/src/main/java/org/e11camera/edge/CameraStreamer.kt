package org.e11camera.edge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 摄像头采集器：打开 GC2083，持续取 YUV_420_888 帧 → 转 JPEG → 存最新帧。
 * 外部通过 [latestJpeg] 读取用于 MJPEG 推流。
 *
 * 当前采集分辨率 640×480、目标 30fps（优先兼容设备 Camera HAL）。
 */
class CameraStreamer(private val context: Context) {
    companion object {
        private const val TAG = "CameraStreamer"
        private const val WIDTH = 640
        private const val HEIGHT = 480
    }

    /** 本地预览 Surface（App 前台回显用），为 null 时不回显 */
    @Volatile
    var previewSurface: android.view.Surface? = null

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor = Executors.newSingleThreadExecutor()
    private val callbackThread = HandlerThread("camera-cb").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val desiredRunning = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)

    @Volatile
    @get:Synchronized
    var latestJpeg: ByteArray = ByteArray(0)
        private set

    @Volatile
    private var camera: CameraDevice? = null
    @Volatile
    private var session: CameraCaptureSession? = null
    @Volatile
    private var imageReader: ImageReader? = null

    /** H.264 硬件编码器（用于 RTSP 推流）*/
    @Volatile
    var h264Encoder: H264Encoder? = null
        private set

    val isRunning: Boolean
        get() = camera != null

    @SuppressLint("MissingPermission")
    fun start() {
        desiredRunning.set(true)
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!desiredRunning.get() || camera != null || !opening.compareAndSet(false, true)) {
            return
        }

        // 启动 H.264 编码器
        if (h264Encoder == null) {
            val enc = H264Encoder(WIDTH, HEIGHT)
            enc.start()
            h264Encoder = enc
        }
        val camId = findFrontCamera() ?: run {
            Log.e(TAG, "找不到摄像头")
            opening.set(false)
            scheduleRetry()
            return
        }
        try {
            cameraManager.openCamera(camId, executor as java.util.concurrent.Executor, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    opening.set(false)
                    if (!desiredRunning.get()) {
                        c.close()
                        return
                    }
                    camera = c
                    startCapture(c)
                }

                override fun onDisconnected(c: CameraDevice) {
                    opening.set(false)
                    c.close()
                    camera = null
                    scheduleRetry()
                }

                override fun onError(c: CameraDevice, error: Int) {
                    opening.set(false)
                    Log.e(TAG, "Camera onError=$error")
                    c.close()
                    camera = null
                    scheduleRetry()
                }
            })
        } catch (e: Exception) {
            opening.set(false)
            Log.e(TAG, "openCamera 失败", e)
            scheduleRetry()
        }
    }

    private fun scheduleRetry(delayMs: Long = 1500L) {
        if (!desiredRunning.get()) return
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private val retryRunnable = Runnable {
        if (desiredRunning.get() && camera == null) {
            Log.i(TAG, "尝试重新打开摄像头")
            openCamera()
        }
    }

    private fun startCapture(c: CameraDevice) {
        // 编码器 Surface（零拷贝主路径）
        val encSurface = h264Encoder?.inputSurface

        // 组装输出 Surface：只用编码器 + 预览（去掉 ImageReader 省CPU）
        val targets = ArrayList<Surface>()
        if (encSurface != null) targets.add(encSurface)
        previewSurface?.let { targets.add(it) }
        // 如果编码器 Surface 不可用，回退到 ImageReader
        if (encSurface == null) {
            val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ onImage(it) }, handler)
            imageReader = reader
            targets.add(reader.surface)
        }

        try {
            c.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        // Surface 变化可能触发会话重建；旧会话的回调仍可能晚到。
                        // 此时 CameraDevice 已关闭，必须丢弃旧回调，不能继续创建请求。
                        if (!desiredRunning.get() || camera !== c) {
                            Log.i(TAG, "忽略已过期的 CaptureSession 回调")
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            if (encSurface != null) req.addTarget(encSurface)
                            imageReader?.let { req.addTarget(it.surface) }
                            previewSurface?.takeIf { it.isValid }?.let { req.addTarget(it) }
                            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            req.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                android.util.Range(30, 30)
                            )
                            s.setRepeatingRequest(req.build(), null, handler)
                            Log.i(
                                TAG,
                                "采集已启动 ${WIDTH}x$HEIGHT Surface=${encSurface != null} targets=${targets.size}"
                            )
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "CameraDevice 状态已变化，重新建立采集会话", e)
                            s.close()
                            restartCapture()
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "预览 Surface 已失效，重新建立采集会话", e)
                            s.close()
                            restartCapture()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession 配置失败")
                        restartCapture()
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startCapture 失败", e)
            restartCapture()
        }
    }

    /**
     * 重新建立 Camera2 会话，但保留编码器和工作线程。
     * 用于预览 Surface 变化、摄像头异常恢复，避免旧实现把线程关闭后再次复用。
     */
    fun restartCapture() {
        if (!desiredRunning.get()) return
        handler.post {
            closeCameraOnly()
            scheduleRetry(300)
        }
    }

    private fun closeCameraOnly() {
        try {
            session?.close()
            session = null
            imageReader?.close()
            imageReader = null
            camera?.close()
            camera = null
            opening.set(false)
        } catch (e: Exception) {
            Log.w(TAG, "关闭 Camera2 会话异常", e)
        }
    }

    private var jpegSkipCounter = 0

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        try {
            // H.264 编码走 Surface 零拷贝，不需要在这里喂编码器
            // JPEG 快照：每 15 帧做一次（大幅省 CPU）
            jpegSkipCounter++
            if (jpegSkipCounter >= 30) {
                jpegSkipCounter = 0
                val jpeg = yuvToJpeg(image)
                synchronized(this) {
                    latestJpeg = jpeg
                }
            } else {
                // 不做 JPEG 的帧也要快速丢弃，避免 ImageReader 积压
            }
        } catch (e: Exception) {
            Log.w(TAG, "转码失败", e)
        } finally {
            image.close()
        }
    }

    /** YUV_420_888 → NV21 → JPEG */
    private fun yuvToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val planes = image.planes
        // 转 NV21
        val nv21 = ByteArray(w * h * 3 / 2)
        // Y
        val yBuf = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        // U/V 交错的可能，这里用通用方式填充
        fillPlane(nv21, 0, yBuf, yRowStride, yPixStride, w, h)

        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        val chromaRowStride = Math.max(uRowStride, vRowStride)
        val chromaPixStride = Math.max(uPixStride, vPixStride)
        val chromaW = w / 2
        val chromaH = h / 2
        var off = w * h
        val chromaBuf = ByteArray(chromaW * chromaH * 2)
        val ub = ByteArray(1)
        val vb = ByteArray(1)
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val uIdx = row * uRowStride + col * uPixStride
                val vIdx = row * vRowStride + col * vPixStride
                uBuf.position(uIdx); uBuf.get(ub)
                vBuf.position(vIdx); vBuf.get(vb)
                nv21[off] = vb[0]; off++
                nv21[off] = ub[0]; off++
            }
        }

        // NV21 → JPEG
        val yuvImage = android.graphics.YuvImage(
            nv21, ImageFormat.NV21, w, h, null
        )
        val baos = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, w, h), 30, baos
        )
        return baos.toByteArray()
    }

    private fun fillPlane(
        out: ByteArray, offset: Int, buf: ByteBuffer,
        rowStride: Int, pixStride: Int, w: Int, h: Int
    ) {
        if (pixStride == rowStride) {
            buf.position(0)
            buf.get(out, offset, rowStride * h)
            return
        }
        var rowOff = offset
        for (r in 0 until h) {
            buf.position(r * rowStride)
            buf.get(out, rowOff, w * pixStride)
            rowOff += w * pixStride
        }
    }

    private fun findFrontCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        // 退而求其次：第 0 个
        return cameraManager.cameraIdList.firstOrNull()
    }

    fun stop() {
        desiredRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        try {
            closeCameraOnly()
            callbackThread.quitSafely()
            h264Encoder?.stop()
            h264Encoder = null
            executor.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "stop 异常", e)
        }
        Log.i(TAG, "摄像头已停止")
    }
}
