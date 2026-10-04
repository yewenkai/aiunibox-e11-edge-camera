package org.e11camera.edge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 与 MediaCodec 之间的轻量 GPU 合成器。
 *
 * 摄像头帧保持在 SurfaceTexture/OES 纹理中，由 GLES 直接绘制到硬编码器输入
 * Surface；同时把东八区时间戳作为透明纹理叠加。时间戳因此成为 H.264 画面像素，
 * 在 WebRTC 全屏、画中画、RTSP 和录像中都可见，且不占用网页播放器的点击区域。
 */
class VideoTimestampCompositor(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "TimestampCompositor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val FULL_QUAD = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )
        // 样机送入 SurfaceTexture 的编码画面相对 ImageReader/NPU 自然坐标为镜像。
        // 这里只翻转摄像头纹理，让远程直播和事件短片恢复自然视角；随后单独绘制
        // 的时间戳不会被翻转。本机 SurfaceView 仍保持适合面对设备操作的镜像效果。
        private val CAMERA_UV = floatArrayOf(
            1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f
        )
        // Android Bitmap 的原点在左上，上传到 GLES 后需要翻转 V 轴。
        private val BITMAP_UV = floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
        )
    }

    private val thread = HandlerThread("video-timestamp-gl")
    private lateinit var handler: Handler
    private val ready = CountDownLatch(1)
    private val released = CountDownLatch(1)

    @Volatile
    private var initFailure: Throwable? = null

    @Volatile
    var cameraSurface: Surface? = null
        private set

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglWindowSurface = EGL14.EGL_NO_SURFACE
    private var cameraTexture = 0
    private var timestampTexture = 0
    private var cameraProgram = 0
    private var bitmapProgram = 0
    private lateinit var cameraLocations: ProgramLocations
    private lateinit var bitmapLocations: ProgramLocations
    private var surfaceTexture: SurfaceTexture? = null
    private val transform = FloatArray(16)
    private var timestampBitmap: Bitmap? = null
    private lateinit var timestampCanvas: Canvas
    private lateinit var timestampPositionBuffer: FloatBuffer
    private val timestampBackground = RectF()
    private val timestampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timestampBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
    }
    private val timestampDate = Date()
    private var timestampPadX = 0f
    private var timestampTextBaseline = 0f
    private var timestampCornerRadius = 0f
    private var lastTimestampSecond = -1L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT+08:00")
    }

    private val positionBuffer = floatBuffer(FULL_QUAD)
    private val cameraUvBuffer = floatBuffer(CAMERA_UV)
    private val bitmapUvBuffer = floatBuffer(BITMAP_UV)

    private data class ProgramLocations(
        val position: Int,
        val texCoord: Int,
        val texture: Int,
        val texMatrix: Int = -1
    )

    /** 初始化成功时返回供 Camera2 输出的 Surface；失败时返回 null，调用方可回退直连。 */
    fun start(): Surface? {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try {
                initializeGl()
            } catch (t: Throwable) {
                initFailure = t
                Log.e(TAG, "GPU 时间戳合成初始化失败", t)
                releaseGl()
            } finally {
                ready.countDown()
                if (initFailure != null) thread.quitSafely()
            }
        }
        if (!ready.await(3, TimeUnit.SECONDS)) {
            Log.e(TAG, "GPU 时间戳合成初始化超时")
            stop()
            return null
        }
        return if (initFailure == null) cameraSurface else null
    }

    fun stop() {
        if (!thread.isAlive) return
        handler.post {
            releaseGl()
            released.countDown()
            thread.quitSafely()
        }
        released.await(2, TimeUnit.SECONDS)
    }

    private fun initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0)) {
            "eglChooseConfig failed"
        }
        val config = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglWindowSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglWindowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        bitmapProgram = createProgram(BITMAP_VERTEX_SHADER, BITMAP_FRAGMENT_SHADER)
        cameraLocations = programLocations(cameraProgram, hasTexMatrix = true)
        bitmapLocations = programLocations(bitmapProgram, hasTexMatrix = false)
        // 两个 program 共享固定 attribute 索引，只在画摄像头/时间戳时切换缓冲区。
        GLES20.glEnableVertexAttribArray(cameraLocations.position)
        GLES20.glEnableVertexAttribArray(cameraLocations.texCoord)
        GLES20.glUseProgram(cameraProgram)
        GLES20.glUniform1i(cameraLocations.texture, 0)
        GLES20.glUseProgram(bitmapProgram)
        GLES20.glUniform1i(bitmapLocations.texture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        cameraTexture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        timestampTexture = createTexture(GLES20.GL_TEXTURE_2D)
        initializeTimestampTexture()

        val st = SurfaceTexture(cameraTexture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        surfaceTexture = st
        cameraSurface = Surface(st)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Log.i(TAG, "GPU 时间戳合成已启动 ${width}x$height，文字纹理 ${timestampBitmap?.width}x${timestampBitmap?.height}")
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            drawCamera()
            updateTimestampTextureIfNeeded()
            drawTimestamp()

            val ptsNs = st.timestamp.takeIf { it > 0 } ?: System.nanoTime()
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, ptsNs)
            check(EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)) { "eglSwapBuffers failed" }
        } catch (t: Throwable) {
            Log.e(TAG, "合成视频帧失败", t)
        }
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)
        bindQuad(cameraLocations, positionBuffer, cameraUvBuffer)
        GLES20.glUniformMatrix4fv(
            cameraLocations.texMatrix,
            1, false, transform, 0
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun initializeTimestampTexture() {
        val scale = width / 640f
        val textSize = 20f * scale
        val left = 12f * scale
        val top = 12f * scale
        timestampPadX = 9f * scale
        val padY = 6f * scale
        timestampTextPaint.apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.MONOSPACE
        }
        // 固定格式的等宽数字始终占同样宽度。保留原像素布局，仅裁掉透明区域。
        val textWidth = timestampTextPaint.measureText("2000-00-00 00:00:00")
        val heightPx = textSize + padY * 2
        val region = TimestampOverlayRegion.around(
            width, height, left, top, left + textWidth + timestampPadX * 2, top + heightPx
        )
        timestampBackground.set(
            left - region.left, top - region.top,
            left + textWidth + timestampPadX * 2 - region.left, top + heightPx - region.top
        )
        timestampTextBaseline = top + padY + textSize * 0.82f - region.top
        timestampCornerRadius = 5f * scale
        timestampPositionBuffer = floatBuffer(region.quad(width, height))
        val bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
        timestampBitmap = bitmap
        timestampCanvas = Canvas(bitmap)
        // ARGB_8888 每行四字节对齐；GLES2 的 NPOT 纹理使用 LINEAR + CLAMP_TO_EDGE。
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "timestamp texture allocation failed" }
    }

    private fun updateTimestampTextureIfNeeded() {
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second == lastTimestampSecond) return

        val bitmap = timestampBitmap ?: return
        timestampCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        timestampDate.time = now
        val text = dateFormat.format(timestampDate)
        timestampCanvas.drawRoundRect(
            timestampBackground, timestampCornerRadius, timestampCornerRadius, timestampBackgroundPaint
        )
        timestampCanvas.drawText(
            text, timestampBackground.left + timestampPadX, timestampTextBaseline, timestampTextPaint
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        lastTimestampSecond = second
    }

    private fun drawTimestamp() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bitmapProgram)
        bindQuad(bitmapLocations, timestampPositionBuffer, bitmapUvBuffer)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindQuad(locations: ProgramLocations, positions: FloatBuffer, uv: FloatBuffer) {
        positions.position(0)
        uv.position(0)
        GLES20.glVertexAttribPointer(locations.position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(locations.texCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
    }

    private fun programLocations(program: Int, hasTexMatrix: Boolean): ProgramLocations {
        val locations = ProgramLocations(
            GLES20.glGetAttribLocation(program, "aPosition"),
            GLES20.glGetAttribLocation(program, "aTexCoord"),
            GLES20.glGetUniformLocation(program, "uTexture"),
            if (hasTexMatrix) GLES20.glGetUniformLocation(program, "uTexMatrix") else -1
        )
        check(locations.position == 0 && locations.texCoord == 1 && locations.texture >= 0) {
            "invalid shader locations"
        }
        check(!hasTexMatrix || locations.texMatrix >= 0) { "missing texture transform" }
        return locations
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aTexCoord")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun releaseGl() {
        try {
            cameraSurface?.release()
            cameraSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            timestampBitmap?.recycle()
            timestampBitmap = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglWindowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "释放 GPU 合成资源异常", t)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglWindowSurface = EGL14.EGL_NO_SURFACE
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private val CAMERA_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val CAMERA_FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """.trimIndent()

    private val BITMAP_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val BITMAP_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """.trimIndent()
}
