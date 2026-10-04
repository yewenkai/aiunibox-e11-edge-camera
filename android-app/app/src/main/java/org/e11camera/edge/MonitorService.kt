package org.e11camera.edge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ScheduledFuture
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

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
        private const val POSTURE_ALERT_COOLDOWN_MS = 5L * 60L * 1_000L
        private const val GUARDIAN_VISION_REFRESH_TIMEOUT_MS = 600L

        @Volatile
        var instance: MonitorService? = null
    }

    private var streamer: CameraStreamer? = null
    private var motor: MotorController? = null
    private var server: WebServer? = null
    private var viewerWatcher: ViewerWatcher? = null
    private var rtspPublisher: RtspPublisher? = null
    private var deviceState: DeviceStateStore? = null
    private var motionDetector: MotionDetector? = null
    private var cryDetector: CryDetector? = null
    private var microphoneAudioSource: MicrophoneAudioSource? = null
    private var npuDetector: NpuDetector? = null
    private var poseDetector: PoseDetector? = null
    private var poseDiagnostics: PoseDiagnosticsStore? = null
    private var barkNotifier: BarkNotifier? = null
    private var guardianEventStore: GuardianEventStore? = null
    private var eventClipRecorder: EventClipRecorder? = null
    private var healthDiagnostics: HealthDiagnostics? = null
    private var startedAtMs: Long = 0
    private val healthScheduler = Executors.newSingleThreadScheduledExecutor()
    private val videoDemand = VideoDemandPolicy()
    @Volatile private var videoRuntimeMode = VideoDemandPolicy.Mode.MANUAL_PAUSED
    private var runtimeVideoWanted = false
    private var runtimeActivatedAtMs = 0L
    private var runtimeCheckFuture: ScheduledFuture<*>? = null
    private val guardianStartupExecutor = Executors.newSingleThreadExecutor()
    private val guardianStartupGeneration = AtomicLong(0)
    @Volatile private var guardianStartupFuture: Future<*>? = null
    @Volatile private var lastSuppressedAlertAt: Long = 0
    @Volatile private var lastPostureAlertAt: Long = 0
    @Volatile private var lastPostureSuppressedAt: Long = 0
    @Volatile private var initializing = false
    @Volatile private var resourcesClosing = true
    private val visionSelfTestRunning = AtomicBoolean(false)

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
            restartCamera()
            return START_STICKY
        }
        if (streamer != null || initializing) {
            Log.i(TAG, "监控服务已运行，忽略重复启动")
            return START_STICKY
        }
        initializing = true
        try {
            val s = CameraStreamer(this)
            val state = DeviceStateStore(this)
            Thread {
                LedController.allOff()
                state.fillLightLevel = 0
            }.start()
            val m = MotorController(state)
            val bark = BarkNotifier(state)
            val eventStore = GuardianEventStore(this)
            val clipRecorder = EventClipRecorder(eventStore)
            val diagnostics = HealthDiagnostics(this)
            val poseStats = PoseDiagnosticsStore(this)
            val microphone = MicrophoneAudioSource(this)
            microphoneAudioSource = microphone
            val motion = MotionDetector(s, state, m) {
                notifyGuardianEvent(
                    kind = "motion",
                    title = "宝宝守护：检测到动作",
                    summary = "画面检测到持续动作"
                )
            }
            val cry = CryDetector(
                context = this,
                state = state,
                microphone = microphone,
                isLocalPlaybackActive = { false }
            ) { score ->
                notifyGuardianEvent(
                    kind = "cry",
                    title = "宝宝守护：检测到疑似哭声",
                    summary = "本地音频模型检测到哭声候选，匹配分 ${"%.0f".format(score * 100)}%",
                    cryScore = score
                )
            }
            val npu = NpuDetector(this, s, state) { previous, current, observation ->
                recordSupervisionTransition(previous, current, observation)
            }
            val pose = PoseDetector(this, s, state, poseStats) { transition, snapshot ->
                handlePostureTransition(transition, snapshot)
            }

            // 局域网 HTTP 控制服务。
            val security = ApiSecurity()
            val srv = WebServer(this, s, m, state, security, PORT)
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP 服务已启动，端口 $PORT")
            streamer = s
            s.onEncoderReady = { encoder -> startEncoderConsumers(s, encoder) }
            motor = m
            deviceState = state
            server = srv
            motionDetector = motion
            cryDetector = cry
            npuDetector = npu
            poseDetector = pose
            poseDiagnostics = poseStats
            barkNotifier = bark
            guardianEventStore = eventStore
            eventClipRecorder = clipRecorder
            healthDiagnostics = diagnostics
            resourcesClosing = false
            startedAtMs = System.currentTimeMillis()
            diagnostics.recordServiceStart(startedAtMs)

            val watcher = ViewerWatcher(
                markerPath = File(filesDir, "viewer-state/watchers").absolutePath,
                onWatchingChanged = {
                    if (!healthScheduler.isShutdown) {
                        try {
                            healthScheduler.execute { onWatchingChanged() }
                        } catch (_: java.util.concurrent.RejectedExecutionException) { }
                    }
                }
            )
            viewerWatcher = watcher
            watcher.start()
            synchronized(this) {
                if (state.captureEnabled && !state.privacyMode) videoDemand.touch(SystemClock.elapsedRealtime())
                reconcileVideoRuntime()
            }

            // 宝宝守护：开机若已启用则按实体镜头罩状态恢复。
            if (state.babyMonitorEnabled) {
                if (!state.privacyMode) {
                    startConfiguredGuardianMonitoring(s, clipRecorder, motion, cry, npu, pose)
                }
            }

            healthScheduler.scheduleAtFixedRate({
                val current = streamer ?: return@scheduleAtFixedRate
                if (!videoRuntimeMode.needsVideo) return@scheduleAtFixedRate
                if (SystemClock.elapsedRealtime() - runtimeActivatedAtMs < 10_000L) return@scheduleAtFixedRate
                val encoderHealthy = current.h264Encoder?.isOutputHealthy(10_000) == true
                if (!current.isRunning || !encoderHealthy) {
                    Log.w(
                        TAG,
                        "摄像头健康检查失败 camera=${current.isRunning} encoder=$encoderHealthy，准备恢复"
                    )
                    current.restartCapture()
                }
            }, 15, 10, TimeUnit.SECONDS)
            healthScheduler.scheduleAtFixedRate({
                recordHealthSample()
            }, 5, 60, TimeUnit.SECONDS)
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
        guardianStartupExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    private fun cleanupResources() {
        synchronized(this) {
            resourcesClosing = true
            streamer?.onEncoderReady = null
            runtimeVideoWanted = false
            videoRuntimeMode = VideoDemandPolicy.Mode.MANUAL_PAUSED
            runtimeCheckFuture?.cancel(false)
            runtimeCheckFuture = null
            videoDemand.clear()
        }
        cancelGuardianStartup()
        cleanupSafely("HTTP 服务") { server?.stop() }
        cleanupSafely("RTSP 推流") { rtspPublisher?.stop() }
        cleanupSafely("事件短片") { eventClipRecorder?.release() }
        cleanupSafely("动作检测") { motionDetector?.stop() }
        cleanupSafely("NPU 人体检测") { npuDetector?.stop() }
        cleanupSafely("NPU 姿态检测") { poseDetector?.stop() }
        cleanupSafely("观看状态") { viewerWatcher?.stop() }
        cleanupSafely("哭声检测") { cryDetector?.stop() }
        cleanupSafely("共享麦克风") { microphoneAudioSource?.shutdown() }
        cleanupSafely("摄像头编码") { streamer?.stop() }
        cleanupSafely("云台电机") { motor?.shutdown() }
        rtspPublisher = null
        motionDetector = null
        cryDetector = null
        microphoneAudioSource = null
        npuDetector = null
        poseDetector = null
        poseDiagnostics = null
        eventClipRecorder = null
        guardianEventStore = null
        healthDiagnostics = null
        viewerWatcher = null
        server = null
        streamer = null
        motor = null
        deviceState = null
    }

    private inline fun cleanupSafely(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.w(TAG, "$name 停止异常", e)
        }
    }

    /** 获取采集器（供 MainActivity 设置预览 Surface） */
    fun getStreamer(): CameraStreamer? = streamer

    /** 重启摄像头（预览 Surface 变化时调用，重新绑定 capture session） */
    fun restartCamera() {
        if (deviceState?.privacyMode == true || deviceState?.captureEnabled == false) return
        val s = streamer ?: return
        try {
            synchronized(this) { videoDemand.touch(SystemClock.elapsedRealtime()); reconcileVideoRuntime() }
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

    data class RuntimeHealth(
        val state: String, val captureEnabled: Boolean, val privacy: Boolean,
        val cameraExpected: Boolean, val cameraOn: Boolean, val streamReady: Boolean,
        val rtspPublishing: Boolean, val warmingUp: Boolean,
        val activeRequests: Int, val externalDemands: Int
    )

    @Synchronized
    fun runtimeHealth(): RuntimeHealth {
        val state = deviceState
        val s = streamer
        return RuntimeHealth(
            videoRuntimeMode.value, state?.captureEnabled == true, state?.privacyMode == true,
            videoRuntimeMode.needsVideo, s?.isRunning == true,
            s?.h264Encoder?.isOutputHealthy() == true, isRtspPublishing(),
            videoRuntimeMode.needsVideo && SystemClock.elapsedRealtime() - runtimeActivatedAtMs < 10_000L,
            videoDemand.requestCount, videoDemand.externalCount
        )
    }

    /** Held until a media response closes; manual pause/privacy still overrides it. */
    interface VideoLease : AutoCloseable {
        val isActive: Boolean
    }

    @Synchronized
    fun acquireVideoDemand(): VideoLease? {
        if (resourcesClosing) return null
        val state = deviceState ?: return null
        if (!state.captureEnabled || state.privacyMode || streamer == null) return null
        val lease = videoDemand.acquire(SystemClock.elapsedRealtime())
        reconcileVideoRuntime()
        return object : VideoLease {
            private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            override val isActive: Boolean get() = synchronized(this@MonitorService) {
                !closed.get() && !resourcesClosing && videoDemand.hasRequest(lease) &&
                    state.captureEnabled && !state.privacyMode
            }
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(this@MonitorService) {
                    videoDemand.release(lease, SystemClock.elapsedRealtime())
                    reconcileVideoRuntime()
                }
            }
        }
    }

    /** MediaMTX on-demand hook renews this finite lease while its reader exists. */
    @Synchronized
    fun renewMediaDemand(enabled: Boolean): Boolean {
        if (resourcesClosing) return false
        val state = deviceState ?: return false
        if (enabled && (!state.captureEnabled || state.privacyMode)) return false
        videoDemand.renewExternal("mediamtx", enabled, SystemClock.elapsedRealtime())
        reconcileVideoRuntime()
        return true
    }

    @Synchronized
    private fun onWatchingChanged() {
        if (streamer == null) return
        videoDemand.touch(SystemClock.elapsedRealtime())
        reconcileVideoRuntime()
    }

    private fun demandInput(): VideoDemandPolicy.Input {
        val state = deviceState
        return VideoDemandPolicy.Input(
            state?.captureEnabled == true, state?.privacyMode == true,
            GuardianModePolicy.visualEnabled(guardianOperatingMode()),
            viewerWatcher?.isWatching == true, streamer?.previewSurface?.isValid == true
        )
    }

    @Synchronized
    private fun reconcileVideoRuntime() {
        val s = streamer ?: return
        if (resourcesClosing || healthScheduler.isShutdown) return
        val now = SystemClock.elapsedRealtime()
        val input = demandInput()
        val mode = videoDemand.mode(input, now)
        videoRuntimeMode = mode
        if (mode.needsVideo != runtimeVideoWanted) {
            runtimeVideoWanted = mode.needsVideo
            if (mode.needsVideo) {
                runtimeActivatedAtMs = now
                s.resume()
            } else {
                rtspPublisher?.stop()
                rtspPublisher = null
                eventClipRecorder?.stop()
                s.pause()
            }
            Log.i(TAG, "视频运行状态 ${mode.value}")
        }
        runtimeCheckFuture?.cancel(false)
        runtimeCheckFuture = videoDemand.nextCheckAt(input, now)?.let { deadline ->
            healthScheduler.schedule({ reconcileVideoRuntime() }, (deadline - now).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }
    }

    fun isWatching(): Boolean = viewerWatcher?.isWatching == true

    @Synchronized
    fun setPrivacyMode(enabled: Boolean): Boolean {
        val s = streamer ?: return false
        val state = deviceState ?: return false
        if (state.privacyMode == enabled) return true
        state.privacyMode = enabled
        if (enabled) {
            stopGuardianMonitoring()
            videoDemand.clear()
        } else if (state.captureEnabled) {
            videoDemand.touch(SystemClock.elapsedRealtime())
        }
        reconcileVideoRuntime()
        if (!enabled && state.babyMonitorEnabled) startConfiguredGuardianMonitoring(s)
        return true
    }

    /** 手动开关决定是否允许采集，自动待机不修改用户选择。 */
    @Synchronized
    fun setCaptureEnabled(enabled: Boolean): Boolean {
        val s = streamer ?: return false
        val state = deviceState ?: return false
        if (state.captureEnabled == enabled) return true
        state.captureEnabled = enabled
        stopGuardianMonitoring()
        if (enabled && !state.privacyMode) videoDemand.touch(SystemClock.elapsedRealtime()) else videoDemand.clear()
        reconcileVideoRuntime()
        if (state.babyMonitorEnabled && !state.privacyMode) startConfiguredGuardianMonitoring(s)
        return true
    }

    @Synchronized
    fun setBabyMode(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.babyMonitorEnabled = enabled
        if (enabled) {
            if (!state.privacyMode) {
                streamer?.let { startConfiguredGuardianMonitoring(it) }
            }
        } else {
            stopGuardianMonitoring()
        }
        reconcileVideoRuntime()
        return true
    }

    /**
     * 记录实体镜头罩的实际状态。关闭后仅保留音频守护，无观看需求时视频可自动待机，
     * 但宝宝守护停用 NPU、动作检测和事件短片，只保留哭声检测。
     */
    @Synchronized
    fun setLensCoverClosed(closed: Boolean): Boolean {
        val state = deviceState ?: return false
        if (state.lensCoverClosed == closed) return true
        state.lensCoverClosed = closed
        cancelSceneCalibration()
        if (state.babyMonitorEnabled && !state.privacyMode) {
            streamer?.let { startConfiguredGuardianMonitoring(it) }
        }
        reconcileVideoRuntime()
        Log.i(TAG, "实体镜头罩状态已更新 closed=$closed mode=${guardianOperatingMode().apiValue}")
        return true
    }

    private fun startConfiguredGuardianMonitoring(
        currentStreamer: CameraStreamer,
        clipRecorder: EventClipRecorder? = eventClipRecorder,
        motion: MotionDetector? = motionDetector,
        cry: CryDetector? = cryDetector,
        npu: NpuDetector? = npuDetector,
        pose: PoseDetector? = poseDetector
    ) {
        reconcileVideoRuntime()
        when (guardianOperatingMode()) {
            GuardianOperatingMode.NORMAL ->
                startGuardianMonitoring(currentStreamer, clipRecorder, motion, cry, npu, pose)
            GuardianOperatingMode.AUDIO_ONLY ->
                startAudioOnlyGuardianMonitoring(clipRecorder, motion, cry, npu, pose)
            GuardianOperatingMode.OFF,
            GuardianOperatingMode.PRIVACY_PAUSED -> stopGuardianMonitoring()
        }
    }

    @Synchronized
    private fun startGuardianMonitoring(
        currentStreamer: CameraStreamer,
        clipRecorder: EventClipRecorder? = eventClipRecorder,
        motion: MotionDetector? = motionDetector,
        cry: CryDetector? = cryDetector,
        npu: NpuDetector? = npuDetector,
        pose: PoseDetector? = poseDetector
    ) {
        val generation = beginGuardianStartup()
        clipRecorder?.start(currentStreamer.h264Encoder)
        // 动作先进入安全侧守护；音频、NPU 和 Pose 模型加载绝不阻塞主线程。
        motion?.start()
        guardianStartupFuture = guardianStartupExecutor.submit {
            try {
                if (!isGuardianStartupCurrent(generation)) return@submit
                cry?.start()
                if (!isGuardianStartupCurrent(generation)) {
                    if (guardianOperatingMode() != GuardianOperatingMode.AUDIO_ONLY) cry?.stop()
                    return@submit
                }
                npu?.start()
                if (!isGuardianStartupCurrent(generation)) {
                    npu?.stop()
                    return@submit
                }
                if (npu?.warmUpForGuard() != true) {
                    Log.w(TAG, "NPU 未完成后台预热，动作与哭声仍按安全侧继续")
                }
                if (!isGuardianStartupCurrent(generation)) {
                    npu?.stop()
                    return@submit
                }
                pose?.applyConfiguration(visualGuardianRunning = true)
                if (!isGuardianStartupCurrent(generation)) pose?.stop()
            } catch (e: Exception) {
                if (isGuardianStartupCurrent(generation)) {
                    Log.w(TAG, "视觉守护后台启动异常，动作与哭声仍继续", e)
                }
            }
        }
    }

    @Synchronized
    private fun startAudioOnlyGuardianMonitoring(
        clipRecorder: EventClipRecorder? = eventClipRecorder,
        motion: MotionDetector? = motionDetector,
        cry: CryDetector? = cryDetector,
        npu: NpuDetector? = npuDetector,
        pose: PoseDetector? = poseDetector
    ) {
        val generation = beginGuardianStartup()
        clipRecorder?.stop()
        motion?.stop()
        npu?.stop()
        pose?.stop()
        guardianStartupFuture = guardianStartupExecutor.submit {
            if (guardianStartupGeneration.get() != generation ||
                guardianOperatingMode() != GuardianOperatingMode.AUDIO_ONLY
            ) {
                return@submit
            }
            cry?.start()
            if (guardianStartupGeneration.get() != generation ||
                guardianOperatingMode() != GuardianOperatingMode.AUDIO_ONLY
            ) {
                cry?.stop()
                return@submit
            }
            Log.i(TAG, "实体镜头罩关闭：仅音频守护已启动")
        }
    }

    @Synchronized
    private fun stopGuardianMonitoring() {
        cancelGuardianStartup()
        eventClipRecorder?.stop()
        motionDetector?.stop()
        cryDetector?.stop()
        npuDetector?.stop()
        poseDetector?.stop()
    }

    private fun beginGuardianStartup(): Long {
        cancelGuardianStartup()
        return guardianStartupGeneration.get()
    }

    private fun cancelGuardianStartup() {
        guardianStartupGeneration.incrementAndGet()
        guardianStartupFuture?.cancel(true)
        guardianStartupFuture = null
    }

    private fun isGuardianStartupCurrent(generation: Long): Boolean =
        guardianStartupGeneration.get() == generation &&
            !Thread.currentThread().isInterrupted &&
            guardianOperatingMode() == GuardianOperatingMode.NORMAL

    fun setBabySensitivity(level: Int) {
        deviceState?.babySensitivity = level
    }

    @Synchronized
    fun setPoseAnalysisEnabled(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.poseAnalysisEnabled = enabled
        if (!enabled) state.poseNotificationsEnabled = false
        poseDetector?.applyConfiguration(
            visualGuardianRunning = GuardianModePolicy.visualEnabled(guardianOperatingMode())
        )
        return true
    }

    fun setPoseNotificationsEnabled(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.poseNotificationsEnabled = enabled && state.poseAnalysisEnabled
        return true
    }

    fun setPoseDebugOverlayEnabled(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.poseDebugOverlayEnabled = enabled
        return true
    }

    fun setPoseSideRiskEnabled(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.poseSideRiskEnabled = enabled
        return true
    }

    fun setFaceVisibilityEnabled(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.faceVisibilityEnabled = enabled
        poseDetector?.applyConfiguration(
            visualGuardianRunning = GuardianModePolicy.visualEnabled(guardianOperatingMode())
        )
        return true
    }

    fun barkTargets(): List<BarkNotifier.Target> =
        barkNotifier?.targets() ?: emptyList()

    fun barkAdd(name: String, key: String): Boolean =
        barkNotifier?.add(name, key) == true

    fun barkRemove(key: String) {
        barkNotifier?.remove(key)
    }

    fun barkSetEnabled(key: String, enabled: Boolean) {
        barkNotifier?.setEnabled(key, enabled)
    }

    fun isBarkConfigured(): Boolean = barkNotifier?.configured == true

    /** key 为空时测试所有启用目标。 */
    fun sendTestBark(key: String?) {
        if (key.isNullOrBlank()) {
            barkNotifier?.notify("宝宝守护测试", "收到这条说明设备直推链路正常")
        } else {
            barkNotifier?.notifyOne(key, "宝宝守护测试", "收到这条说明该设备推送正常")
        }
    }

    fun invalidateMotionReference() {
        motionDetector?.notifyReferenceInvalid()
    }

    fun isBabyMotionActive(): Boolean = motionDetector?.isActive == true

    fun isBabyCryActive(): Boolean = cryDetector?.isActive == true

    fun isCryModelLoaded(): Boolean = cryDetector?.modelLoaded == true

    fun cryAudioRms(): Float = cryDetector?.lastAudioRms ?: 0f

    fun cryScore(): Float = cryDetector?.lastCryScore ?: 0f

    fun cryThreshold(): Float = cryDetector?.currentThreshold ?: 0f

    fun crySilenceRmsGate(): Float = cryDetector?.silenceRmsGate ?: 0f

    fun cryTopClassIndex(): Int = cryDetector?.topClassIndex ?: -1

    fun cryTopClassScore(): Float = cryDetector?.topClassScore ?: 0f

    fun cryAnalyzedAt(): Long = cryDetector?.lastAnalyzedAt ?: 0L


    fun isNpuModelLoaded(): Boolean = npuDetector?.modelLoaded == true

    fun isPersonPresent(): Boolean = npuDetector?.personPresent == true

    fun personConfidence(): Float = npuDetector?.personConfidence ?: 0f

    fun personBox(): FloatArray = npuDetector?.personBox ?: FloatArray(0)

    fun personCount(): Int = npuDetector?.personCount ?: 0

    fun adultLikePersonCount(): Int = npuDetector?.adultLikePersonCount ?: 0

    fun caregiverEvidenceNow(): Boolean = npuDetector?.currentDecisionSnapshot()?.let {
        it.personCount >= 2 || it.adultLikePersonCount > 0
    } == true

    fun personBoxes(): List<FloatArray> = npuDetector?.personBoxes ?: emptyList()

    fun npuAnalyzedAt(): Long = npuDetector?.lastAnalyzedAt ?: 0L

    fun npuSourceCapturedAt(): Long = npuDetector?.lastSourceCapturedAt ?: 0L

    fun npuInferenceMs(): Long = npuDetector?.lastInferenceMs ?: 0L

    fun npuAlertRefreshStatus(): String = npuDetector?.lastAlertRefreshStatus ?: "unavailable"

    fun npuAlertRefreshAt(): Long = npuDetector?.lastAlertRefreshAt ?: 0L

    fun npuAlertRefreshDiagnostics(): JSONObject {
        val npu = npuDetector
        return JSONObject()
            .put("queueMs", npu?.lastAlertRefreshQueueMs ?: 0L)
            .put("captureMs", npu?.lastAlertRefreshCaptureMs ?: 0L)
            .put("totalWaitMs", npu?.lastAlertRefreshTotalMs ?: 0L)
            .put("sourceAgeMs", npu?.lastAlertSourceAgeMs ?: -1L)
            .put("degradedReason", npu?.lastAlertDegradedReason ?: "unavailable")
    }

    fun isPoseModelAvailable(): Boolean = poseDetector?.poseModelAvailable == true

    fun isPoseModelLoaded(): Boolean = poseDetector?.modelLoaded == true

    fun isFaceModelAvailable(): Boolean = poseDetector?.faceModelAvailable == true

    fun isFaceModelLoaded(): Boolean = poseDetector?.faceModelLoaded == true

    fun poseSnapshot(): PoseAnalysisSnapshot = poseDetector?.latest ?: PoseAnalysisSnapshot()

    fun poseDiagnosticsSummary(): PoseDiagnosticsSummary? = poseDiagnostics?.summary()

    fun recordPoseFeedback(value: String): Boolean = poseDetector?.recordFeedback(value) == true

    fun runPoseSelfTest(): PoseAnalysisSnapshot? {
        val lease = acquireVideoDemand() ?: return null
        return try {
            poseDetector?.selfTest()
        } finally {
            lease.close()
        }
    }

    /** 独立人体模型和告警取帧自测；不启动真实守护、不保存图像、不接入通知回调。 */
    fun runVisionSelfTest(): JSONObject {
        fun failure(reason: String) = JSONObject().put("ok", false).put("error", reason)
        if (!visionSelfTestRunning.compareAndSet(false, true)) return failure("self_test_busy")
        var lease: AutoCloseable? = null
        var detector: NpuDetector? = null
        try {
            val state = deviceState ?: return failure("service_unavailable")
            val currentStreamer = streamer ?: return failure("service_unavailable")
            if (GuardianModePolicy.visualEnabled(guardianOperatingMode())) return failure("guardian_running")
            lease = acquireVideoDemand() ?: return failure("capture_disabled_or_privacy")
            val isolated = NpuDetector(this, currentStreamer, state) { _, _, _ -> }
            detector = isolated
            isolated.start()
            if (!isolated.modelLoaded) return failure("model_unavailable")
            val warmed = isolated.warmUpForGuard()
            val samples = JSONArray()
            var allRefreshed = warmed
            repeat(3) {
                if (!state.captureEnabled || state.privacyMode) return failure("capture_disabled_or_privacy")
                if (GuardianModePolicy.visualEnabled(guardianOperatingMode())) return failure("guardian_running")
                val result = isolated.refreshForAlert(GUARDIAN_VISION_REFRESH_TIMEOUT_MS, forceFresh = true)
                allRefreshed = allRefreshed && result.refreshStatus == "refreshed"
                samples.put(
                    JSONObject().put("refreshStatus", result.refreshStatus)
                        .put("sourceCapturedAt", result.sourceCapturedAt)
                        .put("analyzedAt", result.analyzedAt)
                        .put("sourceAgeMs", result.sourceAgeMs)
                        .put("queueMs", result.queueMs)
                        .put("captureMs", result.captureMs)
                        .put("inferenceMs", result.inferenceMs)
                        .put("totalWaitMs", result.totalWaitMs)
                        .put("degradedReason", result.degradedReason)
                )
            }
            return JSONObject().put("ok", allRefreshed).put("modelLoaded", isolated.modelLoaded)
                .put("warmupSucceeded", warmed).put("timeoutMs", GUARDIAN_VISION_REFRESH_TIMEOUT_MS)
                .put("sampleCount", samples.length()).put("samples", samples)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "无通知视觉取帧自测失败", e)
            return failure(e.javaClass.simpleName)
        } finally {
            try {
                detector?.stop()
            } finally {
                try { lease?.close() } finally { visionSelfTestRunning.set(false) }
            }
        }
    }

    fun startAmbientCalibration(): Boolean =
        GuardianModePolicy.audioEnabled(guardianOperatingMode()) &&
            cryDetector?.startAmbientCalibration() == true

    fun startAdultCalibration(): Boolean =
        GuardianModePolicy.visualEnabled(guardianOperatingMode()) &&
            npuDetector?.startAdultCalibration() == true

    fun cancelSceneCalibration() {
        cryDetector?.cancelCalibration()
        npuDetector?.cancelAdultCalibration()
    }

    fun resetSceneCalibration() {
        deviceState?.resetSceneCalibration()
        cryDetector?.resetCalibration()
        npuDetector?.resetAdultCalibration()
    }

    fun cryCalibrationStatus(): CryCalibrationStatus =
        cryDetector?.calibrationStatus() ?: CryCalibrationStatus(
            state = "unavailable", startedAt = 0L, calibratedAt = 0L,
            sampleCount = 0, targetSamples = 15, noiseRms = 0f, noiseScore = 0f
        )

    fun adultCalibrationStatus(): AdultCalibrationStatus =
        npuDetector?.adultCalibrationStatus() ?: AdultCalibrationStatus(
            state = "unavailable", startedAt = 0L, calibratedAt = 0L,
            sampleCount = 0, targetSamples = 8, currentPersonCount = 0,
            medianHeight = 0f, medianArea = 0f, minHeight = 0.48f, minArea = 0.14f
        )

    fun supervisionState(): String = if (GuardianModePolicy.visualEnabled(guardianOperatingMode())) {
        (npuDetector?.currentDecisionSnapshot()?.supervisionState ?: SupervisionStateMachine.State.UNKNOWN).name.lowercase()
    } else {
        SupervisionStateMachine.State.UNKNOWN.name.lowercase()
    }

    fun notificationsArmed(): Boolean =
        !GuardianModePolicy.visualEnabled(guardianOperatingMode()) ||
            npuDetector?.currentDecisionSnapshot()?.notificationsSuppressed != true

    fun lastSuppressedAlertAt(): Long = lastSuppressedAlertAt

    fun isBabyModeEnabled(): Boolean = deviceState?.babyMonitorEnabled == true

    fun isPrivacyModeOn(): Boolean = deviceState?.privacyMode == true

    fun isLensCoverClosed(): Boolean = deviceState?.lensCoverClosed == true

    fun guardianOperatingMode(): GuardianOperatingMode {
        val state = deviceState ?: return GuardianOperatingMode.OFF
        return GuardianModePolicy.resolve(
            guardianEnabled = state.babyMonitorEnabled,
            privacyMode = state.privacyMode,
            lensCoverClosed = state.lensCoverClosed || !state.captureEnabled
        )
    }

    fun babyLastEventAt(): Long? = guardianEventStore?.list(1)?.firstOrNull()?.lastOccurredAt

    /** 动作+哭声事件合并（新→旧），kind: "motion" | "cry"。 */
    fun babyRecentEvents(): List<Pair<Long, String>> {
        return (guardianEventStore?.list(50) ?: emptyList())
            .flatMap { event ->
                if (event.kind == "incident") {
                    event.observations.filter { it.kind == "motion" || it.kind == "cry" }
                        .map { it.occurredAt to it.kind }
                } else if (event.kind == "motion" || event.kind == "cry") {
                    listOf(event.occurredAt to event.kind)
                } else {
                    emptyList()
                }
            }
            .sortedByDescending { it.first }
            .take(20)
    }

    fun guardianEvents(limit: Int): List<GuardianEventEntry> =
        guardianEventStore?.list(limit) ?: emptyList()

    fun setGuardianEventFeedback(eventId: String, value: String): Boolean =
        guardianEventStore?.setFeedback(eventId, value) == true

    fun guardianClipFile(eventId: String): java.io.File? =
        guardianEventStore?.clipFile(eventId)

    fun deleteGuardianClip(eventId: String): Boolean =
        guardianEventStore?.deleteClip(eventId) == true

    fun clearGuardianHistoryClips(): GuardianClipCleanupResult =
        guardianEventStore?.clearHistoryClips() ?: GuardianClipCleanupResult(0, 0, 0L)

    fun guardianClipStorageInfo(): GuardianClipStorageInfo =
        guardianEventStore?.storageInfo()
            ?: GuardianClipStorageInfo(0, 0L, GuardianEventStore.MAX_CLIPS, GuardianEventStore.MAX_CLIP_BYTES)

    fun healthSummary(): DeviceHealthSummary? = healthDiagnostics?.summary()

    private fun recordHealthSample() {
        try {
            val diagnostics = healthDiagnostics ?: return
            if (deviceState == null) return
            val currentStreamer = streamer ?: return
            val encoder = currentStreamer.h264Encoder
            val cameraExpected = videoRuntimeMode.needsVideo
            val guardianMode = guardianOperatingMode()
            diagnostics.sample(
                DeviceHealthInput(
                    cameraExpected = cameraExpected,
                    cameraOn = currentStreamer.isRunning,
                    frameCapturedAt = currentStreamer.latestFrameCapturedAtMs,
                    streamReady = encoder?.isOutputHealthy() == true,
                    encodedFrameCount = encoder?.outputFrameCount ?: 0L,
                    npuExpected = GuardianModePolicy.visualEnabled(guardianMode),
                    npuLoaded = npuDetector?.modelLoaded == true,
                    npuAnalyzedAt = npuDetector?.lastAnalyzedAt ?: 0L,
                    npuInferenceMs = npuDetector?.lastInferenceMs ?: 0L,
                    audioExpected = GuardianModePolicy.audioEnabled(guardianMode),
                    audioLoaded = cryDetector?.modelLoaded == true,
                    audioAnalyzedAt = cryDetector?.lastAnalyzedAt ?: 0L,
                    freeStorageBytes = filesDir.usableSpace,
                    clipStorageBytes = guardianEventStore?.storageInfo()?.totalBytes ?: 0L
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "记录设备健康样本失败", e)
        }
    }

    /** 手动验证端侧短片链路，不发送 Bark。 */
    fun createGuardianTestClip(): Boolean {
        if (!GuardianModePolicy.clipEnabled(guardianOperatingMode())) return false
        val store = guardianEventStore ?: return false
        val recorder = eventClipRecorder ?: return false
        val vision = npuDetector?.currentDecisionSnapshot()
        val event = store.addTriggered(
            kind = "test",
            title = "短片录制测试",
            summary = "手动发起端侧画面短片测试",
            decision = "recorded",
            reason = "仅验证事件前后画面的保存和播放，不会发送 Bark 通知",
            personCount = vision?.personCount ?: 0,
            adultLikePersonCount = vision?.adultLikePersonCount ?: 0,
            supervisionState = (vision?.supervisionState ?: SupervisionStateMachine.State.UNKNOWN)
                .name.lowercase(),
            cryScore = null
        )
        return recorder.capture(event.id)
    }

    private fun checkedVisionDecision(vision: AlertVisionSnapshot?): AlertVisionSnapshot? {
        vision ?: return null
        val maxAgeMs = if (vision.refreshStatus in setOf(
                "refreshed", "latest_frame", "current_state", "supervision_transition"
            )) AlertVisionRefreshPolicy.MAX_SOURCE_AGE_MS else AlertVisionRefreshPolicy.RECENT_SOURCE_REUSE_MS
        return AlertVisionRefreshPolicy.safeDecision(
            vision,
            AlertVisionRefreshPolicy.sourceAgeMs(
                System.currentTimeMillis(), vision.sourceCapturedAt,
                SystemClock.elapsedRealtime(), vision.sourceCapturedElapsedMs
            ),
            npuDetector?.modelLoaded == true, maxAgeMs
        )
    }

    private fun visionDegradedReason(vision: AlertVisionSnapshot?): String? {
        val current = vision ?: return null
        val reason = when (current.degradedReason) {
            "" -> return null
            "source_expired" -> "源画面过期，历史成人证据已排除"
            "detector_stopped" -> "人体检测已停止，本次陪护未知"
            "fresh_capture_timeout_used_cache" -> "新帧等待到期，使用时效范围内已有画面"
            "queue_deadline" -> "排队耗尽等待预算，未增加额外采集"
            "capture_deadline", "total_deadline" -> "已用完视觉复核等待预算"
            "no_analysis", "no_frame" -> "没有有效人体分析画面"
            else -> "本次视觉复核未完整完成"
        }
        return if (current.sourceAgeMs >= 0L) "$reason，源画面年龄 ${current.sourceAgeMs}ms" else reason
    }

    /**
     * 正常守护在通知前复核成人陪护证据；仅音频守护不读取画面，哭声按安全侧处理。
     * 两种模式都写入事件解释，并在同一 60 秒会话中最多通知一次。
     */
    private fun notifyGuardianEvent(
        kind: String,
        title: String,
        summary: String,
        cryScore: Float? = null,
        visionOverride: AlertVisionSnapshot? = null,
        decisionContext: String? = null
    ) {
        val operatingMode = guardianOperatingMode()
        if (!GuardianModePolicy.audioEnabled(operatingMode)) return
        if (kind == "motion" && !GuardianModePolicy.visualEnabled(operatingMode)) return

        val audioOnly = operatingMode == GuardianOperatingMode.AUDIO_ONLY
        val npu = npuDetector
        val obtainedVision = if (audioOnly) null else {
            visionOverride ?: npu?.refreshForAlert(GUARDIAN_VISION_REFRESH_TIMEOUT_MS)
                ?: npu?.currentDecisionSnapshot("unavailable")
        }
        if (guardianOperatingMode() != operatingMode) return
        val vision = checkedVisionDecision(obtainedVision)
        val personCount = if (audioOnly) 0 else vision?.personCount ?: 0
        val adultLikeCount = if (audioOnly) 0 else {
            vision?.adultLikePersonCount ?: 0
        }
        val suppressed = if (audioOnly) false else {
            vision?.notificationsSuppressed == true
        }
        val configured = barkNotifier?.configured == true
        val decision = when {
            suppressed -> "suppressed"
            configured -> "notified"
            else -> "not_configured"
        }
        val decisionReason = when {
            audioOnly && configured ->
                "实体镜头罩已关闭，无法判断成人陪护；仅根据疑似哭声按安全侧发送通知"
            audioOnly ->
                "实体镜头罩已关闭，无法判断成人陪护；已记录疑似哭声，但尚未配置可用的通知设备"
            suppressed && personCount >= 2 ->
                "当前画面检测到 ${personCount} 人，按有人陪护处理，因此未发送通知"
            suppressed && adultLikeCount > 0 ->
                "当前检测到成人体型证据，按有人陪护处理，因此未发送通知"
            suppressed ->
                "刚刚确认过有人陪护，仍在离开缓冲期，因此未发送通知"
            configured ->
                "未检测到明确成人，已触发 Bark 通知"
            else ->
                "未检测到明确成人，但尚未配置可用的通知设备"
        }
        val refreshStatus = if (audioOnly) "lens_covered" else vision?.refreshStatus ?: "unavailable"
        val refreshReason = when (refreshStatus) {
            "lens_covered" -> "实体镜头罩关闭，本次未采集或分析画面"
            "refreshed" -> "通知前已补跑一次新画面 NPU"
            "recent_frame" -> "最近一次 NPU 画面不足 0.5 秒，直接复用同一陪护结果"
            "latest_frame" -> "未在限时内取到新帧，已用最近画面补跑 NPU"
            "supervision_transition" -> "成人离开后使用刚完成的陪护分析重新决策"
            "timeout" ->
                "NPU 补跑等待超过 ${GUARDIAN_VISION_REFRESH_TIMEOUT_MS}ms，仅接受 0.5 秒内已有证据，过期结果不抑制通知"
            "no_frame" -> "当前没有可用新画面，过期人体结果不参与通知抑制"
            "stale_frame" -> "源画面已过期，历史成人结果已排除，本次陪护未知"
            "unavailable", "failed", "interrupted" -> "NPU 补跑不可用，过期人体结果不参与通知抑制"
            else -> "NPU 补跑状态：$refreshStatus"
        }
        val reason = listOfNotNull(decisionReason, refreshReason, visionDegradedReason(vision), decisionContext)
            .joinToString("；")
        val mutation = guardianEventStore?.addOrMergeTriggered(
            kind = kind,
            summary = summary,
            decision = decision,
            reason = reason,
            personCount = personCount,
            adultLikePersonCount = adultLikeCount,
            supervisionState = if (audioOnly) {
                SupervisionStateMachine.State.UNKNOWN.name.lowercase()
            } else {
                (vision?.supervisionState
                    ?: SupervisionStateMachine.State.UNKNOWN).name.lowercase()
            },
            cryScore = cryScore,
            visionRefreshStatus = refreshStatus,
            visionAnalyzedAt = vision?.analyzedAt ?: 0L,
            visionSourceCapturedAt = vision?.sourceCapturedAt ?: 0L,
            visionInferenceMs = vision?.inferenceMs ?: 0L,
            canNotify = !suppressed && configured,
            captureClip = GuardianModePolicy.clipEnabled(operatingMode)
        )
        if (GuardianModePolicy.clipEnabled(operatingMode)) mutation?.let {
            when {
                it.created -> eventClipRecorder?.capture(it.event.id)
                it.event.clipStatus == GuardianEventStore.CLIP_NONE &&
                    guardianEventStore?.requestClip(it.event.id) == true ->
                    eventClipRecorder?.capture(it.event.id)
                else -> eventClipRecorder?.extend(it.event.id)
            }
        }

        if (suppressed) {
            lastSuppressedAlertAt = System.currentTimeMillis()
            Log.i(TAG, "检测到${title}，当前有人陪护，仅记录事件和短片，不发送 Bark")
            return
        }
        if (mutation?.shouldNotify == true) {
            val detail = if (audioOnly) {
                "$summary；当前为仅音频守护，无法确认成人是否在场"
            } else {
                "$summary，打开控制页查看实时画面"
            }
            barkNotifier?.notify(title, detail)
        } else if (configured) {
            Log.i(TAG, "检测到$title，已合并到一分钟事件会话，不重复发送 Bark")
        }
    }

    /**
     * 稳定姿态首次进入风险候选时才到这里。影子模式和调试骨架始终不经过此方法；
     * 即使用户手动打开姿态通知，只要当前检测到两人或成人证据也绝不外发。
     */
    private fun handlePostureTransition(
        transition: PostureTransition,
        snapshot: PoseAnalysisSnapshot
    ) {
        val state = deviceState ?: return
        if (!state.poseNotificationsEnabled) return
        if (!GuardianModePolicy.visualEnabled(guardianOperatingMode())) return
        if (!PostureClassificationPolicy.isRisk(transition.current, state.poseSideRiskEnabled)) return

        val now = System.currentTimeMillis()
        val npu = npuDetector
        val currentNpuVision = npu?.currentDecisionSnapshot()
        val poseSourceAge = AlertVisionRefreshPolicy.sourceAgeMs(
            now, snapshot.sourceCapturedAt, SystemClock.elapsedRealtime(), 0L
        )
        val poseEvidenceUsable = AlertVisionRefreshPolicy.sourceUsable(poseSourceAge)
        val poseAdultPresent = poseEvidenceUsable &&
            (snapshot.personCount >= 2 || snapshot.adultLikePersonCount > 0)
        val currentNpuAdultPresent = (currentNpuVision?.personCount ?: 0) >= 2 ||
            (currentNpuVision?.adultLikePersonCount ?: 0) > 0 ||
            currentNpuVision?.notificationsSuppressed == true

        // 已有成人证据时无需为通知补跑；证据消失且冷却期已过，才取新帧做最后复核。
        if (!poseAdultPresent && !currentNpuAdultPresent &&
            now - lastPostureAlertAt < POSTURE_ALERT_COOLDOWN_MS
        ) {
            Log.i(TAG, "姿态风险候选仍在 5 分钟冷却期，仅保留影子统计")
            return
        }
        val vision = if (poseAdultPresent || currentNpuAdultPresent) {
            currentNpuVision
        } else {
            npu?.refreshForAlert(GUARDIAN_VISION_REFRESH_TIMEOUT_MS)
        }.let { checkedVisionDecision(it) }
        if (!state.poseNotificationsEnabled || !GuardianModePolicy.visualEnabled(guardianOperatingMode())) return
        val refreshedAdultPresent = (vision?.personCount ?: 0) >= 2 ||
            (vision?.adultLikePersonCount ?: 0) > 0 || vision?.notificationsSuppressed == true
        val poseEvidenceStillUsable = AlertVisionRefreshPolicy.sourceUsable(
            AlertVisionRefreshPolicy.sourceAgeMs(
                System.currentTimeMillis(), snapshot.sourceCapturedAt, SystemClock.elapsedRealtime(), 0L
            )
        )
        val adultPresent = (poseEvidenceStillUsable &&
            (snapshot.personCount >= 2 || snapshot.adultLikePersonCount > 0)) || refreshedAdultPresent

        val personCount = maxOf(
            if (poseEvidenceStillUsable) snapshot.personCount else 0,
            vision?.personCount ?: 0
        )
        val adultLikeCount = maxOf(
            if (poseEvidenceStillUsable) snapshot.adultLikePersonCount else 0,
            vision?.adultLikePersonCount ?: 0
        )
        if (adultPresent && transition.previous == transition.current &&
            now - lastPostureSuppressedAt < POSTURE_ALERT_COOLDOWN_MS
        ) {
            return
        }
        if (!adultPresent) lastPostureAlertAt = now else lastPostureSuppressedAt = now
        val configured = barkNotifier?.configured == true
        val decision = when {
            adultPresent -> "suppressed"
            configured -> "notified"
            else -> "not_configured"
        }
        val postureName = when (transition.current) {
            PostureLabel.PRONE -> "疑似俯卧"
            PostureLabel.SIDE -> "疑似侧卧"
            PostureLabel.SUPINE -> "疑似仰卧"
            PostureLabel.UNKNOWN -> "姿态不明确"
        }
        val faceReason = when (snapshot.faceStatus) {
            FaceVisibilityStatus.VISIBLE ->
                "按需头部分析仍检出面部，面部可见性 ${"%.0f".format(snapshot.faceScore * 100)}%"
            FaceVisibilityStatus.NOT_VISIBLE ->
                "按需头部分析未确认面部可见，作为遮挡风险的附加证据"
            FaceVisibilityStatus.NO_HEAD_CROP -> "关键点不足，未能可靠裁出头部区域"
            FaceVisibilityStatus.MODEL_UNAVAILABLE -> "面部可见性模型当前不可用"
            FaceVisibilityStatus.DISABLED -> "面部可见性分析未启用"
            FaceVisibilityStatus.IDLE -> "本次没有运行面部可见性分析"
        }
        val refreshReason = when (vision?.refreshStatus) {
            "refreshed" -> "通知前已补跑一次新画面人体 NPU"
            "recent_frame" -> "最近一次人体 NPU 画面不足 0.5 秒，直接复用"
            "latest_frame" -> "未及时取得新帧，已用最近画面补跑人体 NPU"
            "timeout" ->
                "人体 NPU 补跑超过 ${GUARDIAN_VISION_REFRESH_TIMEOUT_MS}ms，仅接受 0.5 秒内已有证据，过期结果不抑制通知"
            "no_frame" -> "当前没有可用新画面，过期人体结果不参与通知抑制"
            "stale_frame" -> "源画面已过期，历史成人结果已排除"
            "unavailable", "failed", "interrupted" -> "人体 NPU 补跑不可用，过期人体结果不参与通知抑制"
            "current_state" -> "使用时效范围内当前人体结果，无需重复补跑"
            null -> "已有成人或陪护证据，无需为通知重复补跑人体 NPU"
            else -> "人体 NPU 补跑状态：${vision.refreshStatus}"
        }
        val adultReason = when {
            personCount >= 2 ->
                "当前画面检测到至少两人，按成人陪护处理，未发送通知"
            adultPresent -> "当前检测到成人体型或陪护缓冲证据，未发送通知"
            configured -> "未检测到明确成人，实验性姿态通知已发送"
            else -> "未检测到明确成人，但尚未配置可用通知设备"
        }
        val event = guardianEventStore?.addTriggered(
            kind = "posture",
            title = "宝宝守护：${postureName}候选",
            summary = "连续 6 次分析中至少 5 次得到${postureName}标签",
            decision = decision,
            reason = listOfNotNull(adultReason, refreshReason, visionDegradedReason(vision), faceReason,
                "这是 2D 关键点启发式，不是医学判断").joinToString("；"),
            personCount = personCount,
            adultLikePersonCount = adultLikeCount,
            supervisionState = (vision?.supervisionState ?: SupervisionStateMachine.State.UNKNOWN)
                .name.lowercase(),
            cryScore = null
        )
        event?.let { eventClipRecorder?.capture(it.id) }
        if (adultPresent) {
            lastSuppressedAlertAt = now
            Log.i(TAG, "检测到$postureName，但存在成人陪护证据，仅记录端侧事件")
            return
        }
        if (configured) {
            barkNotifier?.notify(
                "宝宝守护：${postureName}候选",
                "连续姿态分析得到风险候选；$faceReason。请打开实时画面人工确认"
            )
        }
    }

    private fun recordSupervisionTransition(
        previous: SupervisionStateMachine.State,
        current: SupervisionStateMachine.State,
        observation: SupervisionStateMachine.Observation
    ) {
        if (!GuardianModePolicy.visualEnabled(guardianOperatingMode())) return
        if (previous == current) return
        if (current == SupervisionStateMachine.State.CAREGIVER_PRESENT) {
            val reason = if (observation.totalPeople >= 2) {
                "连续多帧检测到 ${observation.totalPeople} 人，确认有人陪护"
            } else {
                "连续多帧检测到成人体型证据，确认有人陪护"
            }
            guardianEventStore?.addState(
                title = "通知已暂停",
                summary = "检测到成人陪护",
                decision = "suppressed",
                reason = "$reason；动作和哭声仍继续记录",
                personCount = observation.totalPeople,
                adultLikePersonCount = observation.adultLikePeople,
                supervisionState = current.name.lowercase()
            )?.takeIf { !it.created }?.let { eventClipRecorder?.extend(it.event.id) }
        } else if (previous == SupervisionStateMachine.State.CAREGIVER_PRESENT) {
            guardianEventStore?.addState(
                title = "通知已恢复",
                summary = "未再检测到明确成人",
                decision = "armed",
                reason = "陪护证据消失并经过 20 秒缓冲期，动作或哭声事件将重新触发通知",
                personCount = observation.totalPeople,
                adultLikePersonCount = observation.adultLikePeople,
                supervisionState = current.name.lowercase()
            )?.takeIf { !it.created }?.let { eventClipRecorder?.extend(it.event.id) }

            // 哭声和动作检测器只在一个事件的上升沿回调。若事件开始时成人在场，
            // 持续信号在成人离开后不会自然产生第二个上升沿，因此在陪护缓冲结束时
            // 用这次刚完成的 NPU 结果重新决策一次，且不在 NPU 单线程中再次排队等待。
            val now = System.currentTimeMillis()
            val cry = cryDetector
            val motion = motionDetector
            val cryContinues = cry?.hasRecentEvidence(now) == true
            val motionContinues = motion?.hasRecentEvidence(now) == true
            if (cryContinues || motionContinues) {
                val both = cryContinues && motionContinues
                notifyGuardianEvent(
                    kind = if (cryContinues) "cry" else "motion",
                    title = when {
                        both -> "宝宝守护：持续哭声和动作"
                        cryContinues -> "宝宝守护：持续疑似哭声"
                        else -> "宝宝守护：持续动作"
                    },
                    summary = when {
                        both -> "成人离开后，画面动作和疑似哭声仍在持续"
                        cryContinues -> "成人离开后，本地音频模型仍检测到哭声候选"
                        else -> "成人离开后，画面仍检测到持续动作"
                    },
                    cryScore = cry?.lastCryScore?.takeIf { cryContinues && it > 0f },
                    visionOverride = npuDetector?.currentDecisionSnapshot("supervision_transition"),
                    decisionContext = "陪护证据消失并结束 20 秒缓冲后，持续信号已自动重新评估"
                )
            }
        }
    }

    @Synchronized
    private fun startEncoderConsumers(s: CameraStreamer, encoder: H264Encoder) {
        // 内置 RTSP 直推：使用 MediaCodec 原始 PTS，避免裸流经过
        // HTTP/ffmpeg 后时间戳成批、浏览器出现周期性停顿。
        if (resourcesClosing || healthScheduler.isShutdown || streamer !== s || s.currentStreamingEncoder !== encoder || !videoRuntimeMode.needsVideo ||
            deviceState?.captureEnabled != true || deviceState?.privacyMode != false) return
        rtspPublisher?.stop()
        val pub = RtspPublisher(encoder, path = "cam")
        pub.start()
        rtspPublisher = pub
        if (GuardianModePolicy.clipEnabled(guardianOperatingMode())) {
            // 快速暂停/恢复时，旧编码器可能尚未关闭；短片缓存须换绑到新实例。
            eventClipRecorder?.stop()
            eventClipRecorder?.start(encoder)
        }
    }
}
