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
        private val CAMERA_UV = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
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
    private var surfaceTexture: SurfaceTexture? = null
    private val transform = FloatArray(16)
    private var timestampBitmap: Bitmap? = null
    private var lastTimestampSecond = -1L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT+08:00")
    }

    private val positionBuffer = floatBuffer(FULL_QUAD)
    private val cameraUvBuffer = floatBuffer(CAMERA_UV)
    private val bitmapUvBuffer = floatBuffer(BITMAP_UV)

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
        cameraTexture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        timestampTexture = createTexture(GLES20.GL_TEXTURE_2D)

        val st = SurfaceTexture(cameraTexture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        surfaceTexture = st
        cameraSurface = Surface(st)
        timestampBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        GLES20.glViewport(0, 0, width, height)
        Log.i(TAG, "GPU 时间戳合成已启动 ${width}x$height")
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
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
        bindQuad(cameraProgram, cameraUvBuffer)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"),
            1, false, transform, 0
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun updateTimestampTextureIfNeeded() {
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second == lastTimestampSecond) return
        lastTimestampSecond = second

        val bitmap = timestampBitmap ?: return
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val scale = width / 640f
        val textSize = 20f * scale
        val left = 12f * scale
        val top = 12f * scale
        val padX = 9f * scale
        val padY = 6f * scale
        val text = dateFormat.format(Date(now))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val textWidth = paint.measureText(text)
        val heightPx = textSize + padY * 2
        Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.color = Color.argb(170, 0, 0, 0)
            canvas.drawRoundRect(
                RectF(left, top, left + textWidth + padX * 2, top + heightPx),
                5f * scale, 5f * scale, it
            )
        }
        canvas.drawText(text, left + padX, top + padY + textSize * 0.82f, paint)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun drawTimestamp() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(bitmapProgram)
        bindQuad(bitmapProgram, bitmapUvBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bitmapProgram, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindQuad(program: Int, uv: FloatBuffer) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        positionBuffer.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
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
