package org.e11camera.edge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 监控前台服务：持有摄像头采集器 + Web 服务器生命周期。
 * 启动后，局域网可访问 http://设备IP:8080。
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "e11_edge_camera"
        private const val NOTIFICATION_ID = 1
        const val PORT = 8080
        const val ACTION_RESTART_CAMERA = "org.e11camera.edge.RESTART_CAMERA"

        @Volatile
        var instance: MonitorService? = null
    }

    private var streamer: CameraStreamer? = null
    private var motor: MotorController? = null
    private var server: WebServer? = null
    private var viewerWatcher: ViewerWatcher? = null
    private var rtspPublisher: RtspPublisher? = null
    private var startedAtMs: Long = 0
    private val healthScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var initializing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitorService onCreate")
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "启动监控服务")
        startForeground(NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_RESTART_CAMERA) {
            streamer?.restartCapture()
            return START_STICKY
        }
        if (streamer != null || initializing) {
            Log.i(TAG, "监控服务已运行，忽略重复启动")
            return START_STICKY
        }
        initializing = true
        try {
            val s = CameraStreamer(this)
            val m = MotorController()

            // 局域网 HTTP 控制服务。
            val srv = WebServer(this, s, m, PORT)
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP 服务已启动，端口 $PORT")

            s.start()
            streamer = s
            motor = m
            server = srv
            startedAtMs = System.currentTimeMillis()

            // 内置 RTSP 直推：使用 MediaCodec 原始 PTS，避免裸流经过
            // HTTP/ffmpeg 后时间戳成批、浏览器出现周期性停顿。
            s.h264Encoder?.let { enc ->
                val pub = RtspPublisher(enc, path = "cam")
                pub.start()
                rtspPublisher = pub
            }

            // 启动拉流感知（有人看时亮灯）
            val watcher = ViewerWatcher()
            watcher.start()
            viewerWatcher = watcher

            healthScheduler.scheduleAtFixedRate({
                val current = streamer ?: return@scheduleAtFixedRate
                val encoderHealthy = current.h264Encoder?.isOutputHealthy(10_000) == true
                if (!current.isRunning || !encoderHealthy) {
                    Log.w(
                        TAG,
                        "摄像头健康检查失败 camera=${current.isRunning} encoder=$encoderHealthy，准备恢复"
                    )
                    current.restartCapture()
                }
            }, 15, 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "服务启动失败", e)
            cleanupResources()
        } finally {
            initializing = false
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "MonitorService onDestroy")
        healthScheduler.shutdownNow()
        cleanupResources()
        instance = null
        super.onDestroy()
    }

    private fun cleanupResources() {
        try {
            rtspPublisher?.stop()
            viewerWatcher?.stop()
            server?.stop()
            streamer?.stop()
            motor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "停止异常", e)
        }
        rtspPublisher = null
        viewerWatcher = null
        server = null
        streamer = null
        motor = null
    }

    /** 获取采集器（供 MainActivity 设置预览 Surface） */
    fun getStreamer(): CameraStreamer? = streamer

    /** 重启摄像头（预览 Surface 变化时调用，重新绑定 capture session） */
    fun restartCamera() {
        val s = streamer ?: return
        try {
            s.restartCapture()
            Log.i(TAG, "摄像头会话正在重建（含预览）")
        } catch (e: Exception) {
            Log.w(TAG, "重启摄像头异常", e)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "E11 边缘摄像头服务", NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
        val ip = NetworkUtil.getIpAddress(this)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIUniBOX-E11 摄像头运行中")
            .setContentText("http://$ip:$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    fun isWebRunning(): Boolean = try {
        server != null
    } catch (_: Exception) {
        false
    }

    fun uptimeSeconds(): Long =
        if (startedAtMs == 0L) 0 else (System.currentTimeMillis() - startedAtMs) / 1000

    fun isRtspPublishing(): Boolean = rtspPublisher?.connected == true
}
