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
    private var deviceState: DeviceStateStore? = null
    private var motionDetector: MotionDetector? = null
    private var cryDetector: CryDetector? = null
    private var npuDetector: NpuDetector? = null
    private var barkNotifier: BarkNotifier? = null
    private var guardianEventStore: GuardianEventStore? = null
    private var eventClipRecorder: EventClipRecorder? = null
    private var healthDiagnostics: HealthDiagnostics? = null
    private var startedAtMs: Long = 0
    private val healthScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastSuppressedAlertAt: Long = 0
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
            val state = DeviceStateStore(this)
            val m = MotorController(state)
            val bark = BarkNotifier(state)
            val eventStore = GuardianEventStore(this)
            val clipRecorder = EventClipRecorder(eventStore)
            val diagnostics = HealthDiagnostics(this)
            val motion = MotionDetector(s, state, m) {
                notifyGuardianEvent(
                    kind = "motion",
                    title = "宝宝守护：检测到动作",
                    summary = "画面检测到持续动作"
                )
            }
            val cry = CryDetector(this, state) { score ->
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

            // 局域网 HTTP 控制服务。
            val srv = WebServer(this, s, m, state, ApiSecurity(), PORT)
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "HTTP 服务已启动，端口 $PORT")

            streamer = s
            motor = m
            deviceState = state
            server = srv
            motionDetector = motion
            cryDetector = cry
            npuDetector = npu
            barkNotifier = bark
            guardianEventStore = eventStore
            eventClipRecorder = clipRecorder
            healthDiagnostics = diagnostics
            startedAtMs = System.currentTimeMillis()
            diagnostics.recordServiceStart(startedAtMs)

            if (!state.privacyMode) {
                s.start()
                startRtspPublisher(s)
            } else {
                Log.i(TAG, "设备处于隐私模式，暂不启动摄像头")
            }

            // 启动拉流感知（有人看时亮灯）
            val watcher = ViewerWatcher()
            watcher.start()
            viewerWatcher = watcher

            // 宝宝守护：开机若已启用则恢复动作+哭声检测并压制所有 LED
            // （补光灯硬件默认常亮 255，必须主动熄灭）。
            if (state.babyMonitorEnabled) {
                watcher.setLedSuppressed(true)
                Thread { LedController.allOff() }.start()
                if (!state.privacyMode) {
                    clipRecorder.start(s.h264Encoder)
                    motion.start()
                    cry.start()
                    npu.start()
                }
            }

            healthScheduler.scheduleAtFixedRate({
                val current = streamer ?: return@scheduleAtFixedRate
                if (deviceState?.privacyMode == true) return@scheduleAtFixedRate
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
        instance = null
        super.onDestroy()
    }

    private fun cleanupResources() {
        try {
            rtspPublisher?.stop()
            eventClipRecorder?.release()
            motionDetector?.stop()
            cryDetector?.stop()
            npuDetector?.stop()
            viewerWatcher?.stop()
            server?.stop()
            streamer?.stop()
            motor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "停止异常", e)
        }
        rtspPublisher = null
        motionDetector = null
        cryDetector = null
        npuDetector = null
        eventClipRecorder = null
        guardianEventStore = null
        healthDiagnostics = null
        viewerWatcher = null
        server = null
        streamer = null
        motor = null
        deviceState = null
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

    fun isWatching(): Boolean = viewerWatcher?.isWatching == true

    @Synchronized
    fun setPrivacyMode(enabled: Boolean): Boolean {
        val s = streamer ?: return false
        val state = deviceState ?: return false
        if (state.privacyMode == enabled) return true

        state.privacyMode = enabled
        if (enabled) {
            eventClipRecorder?.stop()
            motionDetector?.stop()
            cryDetector?.stop()
            npuDetector?.stop()
            rtspPublisher?.stop()
            rtspPublisher = null
            s.pause()
        } else {
            s.resume()
            startRtspPublisher(s)
            if (state.babyMonitorEnabled) {
                eventClipRecorder?.start(s.h264Encoder)
                motionDetector?.start()
                cryDetector?.start()
                npuDetector?.start()
            }
        }
        return true
    }

    /** 宝宝守护开关：持久化 + 启停动作/哭声检测 + LED 抑制。 */
    @Synchronized
    fun setBabyMode(enabled: Boolean): Boolean {
        val state = deviceState ?: return false
        state.babyMonitorEnabled = enabled
        if (enabled) {
            viewerWatcher?.setLedSuppressed(true)
            Thread { LedController.allOff() }.start()
            if (!state.privacyMode) {
                eventClipRecorder?.start(streamer?.h264Encoder)
                motionDetector?.start()
                cryDetector?.start()
                npuDetector?.start()
            }
        } else {
            eventClipRecorder?.stop()
            motionDetector?.stop()
            cryDetector?.stop()
            npuDetector?.stop()
            viewerWatcher?.setLedSuppressed(false)
        }
        return true
    }

    fun setBabySensitivity(level: Int) {
        deviceState?.babySensitivity = level
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

    fun caregiverEvidenceNow(): Boolean = npuDetector?.caregiverEvidenceNow == true

    fun personBoxes(): List<FloatArray> = npuDetector?.personBoxes ?: emptyList()

    fun npuAnalyzedAt(): Long = npuDetector?.lastAnalyzedAt ?: 0L

    fun npuSourceCapturedAt(): Long = npuDetector?.lastSourceCapturedAt ?: 0L

    fun npuInferenceMs(): Long = npuDetector?.lastInferenceMs ?: 0L

    fun npuAlertRefreshStatus(): String = npuDetector?.lastAlertRefreshStatus ?: "unavailable"

    fun npuAlertRefreshAt(): Long = npuDetector?.lastAlertRefreshAt ?: 0L

    fun startAmbientCalibration(): Boolean =
        deviceState?.babyMonitorEnabled == true && deviceState?.privacyMode != true &&
            cryDetector?.startAmbientCalibration() == true

    fun startAdultCalibration(): Boolean =
        deviceState?.babyMonitorEnabled == true && deviceState?.privacyMode != true &&
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

    fun supervisionState(): String =
        (npuDetector?.supervisionState ?: SupervisionStateMachine.State.UNKNOWN).name.lowercase()

    fun notificationsArmed(): Boolean = npuDetector?.notificationsSuppressed != true

    fun lastSuppressedAlertAt(): Long = lastSuppressedAlertAt

    fun isBabyModeEnabled(): Boolean = deviceState?.babyMonitorEnabled == true

    fun isPrivacyModeOn(): Boolean = deviceState?.privacyMode == true

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
            val state = deviceState ?: return
            val currentStreamer = streamer ?: return
            val encoder = currentStreamer.h264Encoder
            val cameraExpected = !state.privacyMode
            val guardianExpected = state.babyMonitorEnabled && cameraExpected
            diagnostics.sample(
                DeviceHealthInput(
                    cameraExpected = cameraExpected,
                    cameraOn = currentStreamer.isRunning,
                    frameCapturedAt = currentStreamer.latestJpegCapturedAtMs,
                    streamReady = encoder?.isOutputHealthy() == true,
                    encodedFrameCount = encoder?.outputFrameCount ?: 0L,
                    npuExpected = guardianExpected,
                    npuLoaded = npuDetector?.modelLoaded == true,
                    npuAnalyzedAt = npuDetector?.lastAnalyzedAt ?: 0L,
                    npuInferenceMs = npuDetector?.lastInferenceMs ?: 0L,
                    audioExpected = guardianExpected,
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
        val store = guardianEventStore ?: return false
        val recorder = eventClipRecorder ?: return false
        val npu = npuDetector
        val event = store.addTriggered(
            kind = "test",
            title = "短片录制测试",
            summary = "手动发起端侧画面短片测试",
            decision = "recorded",
            reason = "仅验证事件前后画面的保存和播放，不会发送 Bark 通知",
            personCount = npu?.personCount ?: 0,
            adultLikePersonCount = npu?.adultLikePersonCount ?: 0,
            supervisionState = (npu?.supervisionState ?: SupervisionStateMachine.State.UNKNOWN)
                .name.lowercase(),
            cryScore = null
        )
        return recorder.capture(event.id)
    }

    /**
     * 动作和哭声检测始终记录事件。当前帧出现两人或成人体型证据时立即暂停 Bark；
     * 多帧确认后进入陪护缓冲状态。其余 UNKNOWN 按安全侧继续推送，避免人体漏检造成守护空窗。
     */
    private fun notifyGuardianEvent(
        kind: String,
        title: String,
        summary: String,
        cryScore: Float? = null
    ) {
        val npu = npuDetector
        val vision = npu?.refreshForAlert(300L)
        val personCount = vision?.personCount ?: npu?.personCount ?: 0
        val adultLikeCount = vision?.adultLikePersonCount ?: npu?.adultLikePersonCount ?: 0
        val suppressed = vision?.notificationsSuppressed ?: (npu?.notificationsSuppressed == true)
        val configured = barkNotifier?.configured == true
        val decision = when {
            suppressed -> "suppressed"
            configured -> "notified"
            else -> "not_configured"
        }
        val decisionReason = when {
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
        val refreshStatus = vision?.refreshStatus ?: "unavailable"
        val refreshReason = when (refreshStatus) {
            "refreshed" -> "通知前已补跑一次新画面 NPU"
            "latest_frame" -> "未在限时内取到新帧，已用最近画面补跑 NPU"
            "timeout" -> "NPU 补跑等待超过 300ms，本次按最近可用结果判断"
            "no_frame" -> "当前没有可用新画面，本次按最近可用结果判断"
            "unavailable" -> "NPU 当前不可用，不将单次人体结果作为绝对依据"
            else -> "NPU 补跑状态：$refreshStatus"
        }
        val reason = "$decisionReason；$refreshReason"
        val mutation = guardianEventStore?.addOrMergeTriggered(
            kind = kind,
            summary = summary,
            decision = decision,
            reason = reason,
            personCount = personCount,
            adultLikePersonCount = adultLikeCount,
            supervisionState = (vision?.supervisionState
                ?: npu?.supervisionState
                ?: SupervisionStateMachine.State.UNKNOWN).name.lowercase(),
            cryScore = cryScore,
            visionRefreshStatus = refreshStatus,
            visionAnalyzedAt = vision?.analyzedAt ?: 0L,
            visionSourceCapturedAt = vision?.sourceCapturedAt ?: 0L,
            visionInferenceMs = vision?.inferenceMs ?: 0L,
            canNotify = !suppressed && configured
        )
        mutation?.let {
            if (it.created) eventClipRecorder?.capture(it.event.id)
            else eventClipRecorder?.extend(it.event.id)
        }

        if (suppressed) {
            lastSuppressedAlertAt = System.currentTimeMillis()
            Log.i(TAG, "检测到${title}，当前有人陪护，仅记录事件和短片，不发送 Bark")
            return
        }
        if (mutation?.shouldNotify == true) {
            barkNotifier?.notify(title, "$summary，打开控制页查看实时画面")
        } else if (configured) {
            Log.i(TAG, "检测到$title，已合并到一分钟事件会话，不重复发送 Bark")
        }
    }

    private fun recordSupervisionTransition(
        previous: SupervisionStateMachine.State,
        current: SupervisionStateMachine.State,
        observation: SupervisionStateMachine.Observation
    ) {
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
        }
    }

    private fun startRtspPublisher(s: CameraStreamer) {
        // 内置 RTSP 直推：使用 MediaCodec 原始 PTS，避免裸流经过
        // HTTP/ffmpeg 后时间戳成批、浏览器出现周期性停顿。
        s.h264Encoder?.let { enc ->
            val pub = RtspPublisher(enc, path = "cam")
            pub.start()
            rtspPublisher = pub
        }
    }
}
