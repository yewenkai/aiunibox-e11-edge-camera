package org.e11camera.edge

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * 内嵌 HTTP 服务器（NanoHTTPD）。
 * 提供控制网页 + MJPEG 视频流 + REST API。
 *
 * 路由：
 *  GET /                 控制网页
 *  GET /stream           MJPEG 视频流
 *  GET /api/motor        ?dir=left|right|reset [&steps=&speed=]
 *  GET /api/led          ?name=ir&level=255
 *  GET /api/filllight    ?level=0-255
 *  GET /api/ircut        ?on=true|false
 *  GET /api/scene        ?mode=0|1|2
 *  GET /api/baby         ?on=true|false [&sensitivity=0|1|2]（无参数=查询）
 *  GET /api/calibration  ?action=ambient|adult|cancel|reset，场景校准不保存音视频
 *  GET /api/notify       ?add=KEY[&name=备注] ?remove=KEY ?toggle=KEY&on= ?test=KEY|all
 *  GET /api/events       解释时间线；?deleteClip=EVENT_ID 或 ?clearClips=true 清理端侧短片
 *  GET /api/health       滚动 24 小时摄像头、NPU、音频与存储健康统计
 *  GET /event-clip       ?id=EVENT_ID，支持 HTTP Range
 *  GET /api/status       状态 JSON
 */
class WebServer(
    private val context: Context,
    private val streamer: CameraStreamer,
    private val motor: MotorController,
    private val state: DeviceStateStore,
    private val security: ApiSecurity,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServer"
        private const val BOUNDARY = "e11frame"
    }

    @Throws(Exception::class)
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms
        Log.i(TAG, "请求: ${session.method} $uri")
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveIndex()
                uri == "/api/info" -> apiInfo()
                !security.isAuthorized(session) -> unauthorized()
                uri == "/stream" -> serveMjpeg()
                uri == "/snapshot" -> serveSnapshot()
                uri == "/h264" -> serveH264()
                uri == "/event-clip" -> serveEventClip(params["id"], session.headers["range"])
                uri.startsWith("/api/motor") -> apiMotor(params)
                uri.startsWith("/api/preset") -> apiPreset(params)
                uri.startsWith("/api/position") -> apiPosition(params)
                uri.startsWith("/api/led") -> apiLed(params)
                uri.startsWith("/api/statusled") -> apiStatusLed(params)
                uri.startsWith("/api/alloff") -> apiAllOff()
                uri.startsWith("/api/filllight") -> apiFillLight(params)
                uri.startsWith("/api/ircut") -> apiIrcut(params)
                uri.startsWith("/api/scene") -> apiScene(params)
                uri.startsWith("/api/privacy") -> apiPrivacy(params)
                uri.startsWith("/api/baby") -> apiBaby(params)
                uri.startsWith("/api/calibration") -> apiCalibration(params)
                uri.startsWith("/api/notify") -> apiNotify(params)
                uri.startsWith("/api/events") -> apiEvents(params)
                uri.startsWith("/api/health") -> apiHealth()
                uri.startsWith("/api/status") -> apiStatus()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve 异常: $uri", e)
            json("""{"ok":false,"error":"${e.message}"}""")
        }
    }

    /** 单帧 JPEG 快照（前端 JS 定时轮询） */
    private fun serveSnapshot(): Response {
        var frame = streamer.latestJpeg
        var wait = 0
        while (frame.isEmpty() && wait < 100) {
            Thread.sleep(50)
            wait++
            frame = streamer.latestJpeg
        }
        return if (frame.isEmpty()) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "无画面")
        } else {
            newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(frame), frame.size.toLong()
            )
        }
    }

    /** H.264 裸流端点（供 ffmpeg -c copy 转推 RTSP，支持多客户端） */
    private fun serveH264(): Response {
        val enc = streamer.h264Encoder ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "编码器未启动"
        )

        // 每个客户端注册独立订阅队列
        val subQueue = enc.subscribe()

        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 1024 * 1024)

        Thread {
            try {
                var waitCount = 0
                while (!enc.hasSpsPps && waitCount < 200) {
                    Thread.sleep(20); waitCount++
                }
                if (enc.hasSpsPps) {
                    pipeOut.write(enc.getSpsPps())
                }
                while (!Thread.currentThread().isInterrupted) {
                    // 从该客户端专属队列取数据（不影响其他客户端）
                    val nal = subQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (nal != null) {
                        pipeOut.write(nal.data)
                    }
                }
            } catch (_: Exception) {
            } finally {
                enc.unsubscribe(subQueue)
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }.start()

        val resp = newChunkedResponse(
            Response.Status.OK, "video/h264", pipeIn
        )
        resp.addHeader("Cache-Control", "no-cache, private")
        resp.addHeader("Connection", "close")
        return resp
    }

    /** 返回控制网页（从 assets 读取） */
    private fun serveIndex(): Response {
        val html = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        }
    }

    /** MJPEG 流：multipart/x-mixed-replace */
    private fun serveMjpeg(): Response {
        val contentType = "multipart/x-mixed-replace; boundary=$BOUNDARY"
        val stream: InputStream = object : InputStream() {
            private var running = true
            override fun read(): Int {
                // 不使用，走 read(byte[]) 分支
                return -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!running) return -1
                try {
                    var frame = streamer.latestJpeg
                    // 等待第一帧
                    var wait = 0
                    while (frame.isEmpty() && wait < 100) {
                        Thread.sleep(50)
                        wait++
                        frame = streamer.latestJpeg
                    }
                    if (frame.isEmpty()) return -1

                    val sb = StringBuilder()
                    sb.append("--").append(BOUNDARY).append("\r\n")
                    sb.append("Content-Type: image/jpeg\r\n")
                    sb.append("Content-Length: ").append(frame.size).append("\r\n\r\n")
                    val header = sb.toString().toByteArray(Charsets.US_ASCII)
                    val tail = "\r\n".toByteArray(Charsets.US_ASCII)

                    val total = header.size + frame.size + tail.size
                    if (total > len) {
                        // 缓冲不够，说明客户端断开
                        running = false
                        return -1
                    }
                    System.arraycopy(header, 0, b, off, header.size)
                    System.arraycopy(frame, 0, b, off + header.size, frame.size)
                    System.arraycopy(tail, 0, b, off + header.size + frame.size, tail.size)
                    Thread.sleep(66) // ~15fps
                    return total
                } catch (e: Exception) {
                    running = false
                    return -1
                }
            }
        }
        val resp = newChunkedResponse(Response.Status.OK, contentType, stream)
        // 让浏览器知道这是流，不要缓存
        resp.addHeader("Cache-Control", "no-cache, private")
        resp.addHeader("Pragma", "no-cache")
        // 长连接，不超时
        resp.addHeader("Connection", "close")
        return resp
    }

    private fun apiMotor(p: Map<String, String>): Response {
        val action = p["dir"] ?: p["action"]
        val speed = (p["speed"] ?: "400").toIntOrNull() ?: 400
        when (action) {
            "left" -> {
                val steps = (p["steps"] ?: "83").toIntOrNull() ?: 83
                invalidateMotionReference()
                val accepted = motor.turnLeft(steps, speed)
                invalidateMotionReference()
                return json("""{"ok":$accepted,"action":"left","steps":$steps,"speed":$speed,"busy":${motor.isBusy()}${if (accepted) "" else ",\"error\":\"motor_busy\""}}""")
            }
            "right" -> {
                val steps = (p["steps"] ?: "83").toIntOrNull() ?: 83
                invalidateMotionReference()
                val accepted = motor.turnRight(steps, speed)
                invalidateMotionReference()
                return json("""{"ok":$accepted,"action":"right","steps":$steps,"speed":$speed,"busy":${motor.isBusy()}${if (accepted) "" else ",\"error\":\"motor_busy\""}}""")
            }
            "reset" -> {
                // 厂商 -R 是硬件归零流程，不是普通居中；该设备上会持续励磁锁住电机。
                return json("""{"ok":false,"action":"reset","error":"hardware_reset_disabled"}""")
            }
            else -> return json("""{"ok":false,"error":"unknown dir: $action"}""")
        }
    }

    private fun apiPreset(p: Map<String, String>): Response {
        val action = p["action"] ?: "list"
        val name = p["name"] ?: "home"
        val speed = (p["speed"] ?: "400").toIntOrNull() ?: 400
        return when (action) {
            "list" -> json(
                JSONObject()
                    .put("ok", true)
                    .put("presets", JSONArray(state.presetNames()))
                    .toString()
            )
            "save" -> {
                val saved = state.savePreset(name)
                json(
                    JSONObject()
                        .put("ok", saved)
                        .put("action", "save")
                        .put("name", name)
                        .put("positionSteps", state.positionSteps())
                        .apply { if (!saved) put("error", "invalid_preset_name") }
                        .toString()
                )
            }
            "goto" -> {
                val target = state.presetPosition(name)
                    ?: return json("""{"ok":false,"error":"preset_not_found"}""")
                invalidateMotionReference()
                val accepted = motor.moveTo(target, speed)
                invalidateMotionReference()
                json(
                    JSONObject()
                        .put("ok", accepted)
                        .put("action", "goto")
                        .put("name", name)
                        .put("targetSteps", target)
                        .apply { if (!accepted) put("error", "motor_busy") }
                        .toString()
                )
            }
            else -> json("""{"ok":false,"error":"unknown_preset_action"}""")
        }
    }

    private fun apiPosition(p: Map<String, String>): Response {
        return when (p["action"] ?: "get") {
            "get" -> json(
                JSONObject()
                    .put("ok", true)
                    .put("positionSteps", state.positionSteps())
                    .put("positionDegrees", state.positionDegrees())
                    .toString()
            )
            "zero" -> {
                state.setSoftZero()
                json("""{"ok":true,"action":"soft_zero","positionSteps":0}""")
            }
            else -> json("""{"ok":false,"error":"unknown_position_action"}""")
        }
    }

    private fun apiLed(p: Map<String, String>): Response {
        val name = p["name"] ?: "ir"
        val level = (p["level"] ?: "0").toIntOrNull() ?: 0
        invalidateMotionReference()
        if (!LedController.setLed(name, level)) {
            return json("""{"ok":false,"error":"unsupported_led"}""")
        }
        invalidateMotionReference()
        return json("""{"ok":true,"led":"$name","level":$level}""")
    }

    private fun apiStatusLed(p: Map<String, String>): Response {
        val red = (p["r"] ?: "0").toIntOrNull()?.coerceIn(0, 255) ?: 0
        val green = (p["g"] ?: "0").toIntOrNull()?.coerceIn(0, 255) ?: 0
        val blue = (p["b"] ?: "0").toIntOrNull()?.coerceIn(0, 255) ?: 0
        LedController.setLed("red", red)
        LedController.setLed("green", green)
        LedController.setLed("blue", blue)
        return json(
            JSONObject()
                .put("ok", true)
                .put("red", red)
                .put("green", green)
                .put("blue", blue)
                .toString()
        )
    }

    private fun apiAllOff(): Response {
        invalidateMotionReference()
        LedController.allOff()
        invalidateMotionReference()
        return json("""{"ok":true,"action":"all_off"}""")
    }

    private fun apiFillLight(p: Map<String, String>): Response {
        val level = (p["level"] ?: "0").toIntOrNull() ?: 0
        invalidateMotionReference()
        LedController.setFillLight(level)
        invalidateMotionReference()
        state.fillLightLevel = level
        return json("""{"ok":true,"filllight":${level.coerceIn(0, 255)}}""")
    }

    private fun apiIrcut(p: Map<String, String>): Response {
        val on = p["on"]?.toBoolean() ?: false
        invalidateMotionReference()
        LedController.setIrcut(on)
        invalidateMotionReference()
        state.ircutEnabled = on
        return json("""{"ok":true,"ircut":${if (on) "night" else "day"}}""")
    }

    private fun apiScene(p: Map<String, String>): Response {
        val mode = (p["mode"] ?: "0").toIntOrNull() ?: 0
        invalidateMotionReference()
        LedController.setSceneMode(mode)
        invalidateMotionReference()
        state.sceneMode = mode
        val names = arrayOf("day", "night", "auto")
        return json("""{"ok":true,"scene":"${names[mode.coerceIn(0, 2)]}"}""")
    }

    private fun apiPrivacy(p: Map<String, String>): Response {
        val enabled = p["on"]?.toBoolean() ?: false
        val changed = MonitorService.instance?.setPrivacyMode(enabled) == true
        return json(
            JSONObject()
                .put("ok", changed)
                .put("privacy", enabled)
                .apply { if (!changed) put("error", "service_unavailable") }
                .toString()
        )
    }

    /** 宝宝守护：开关、灵敏度、监测区域；无参数时查询当前状态。 */
    private fun apiBaby(p: Map<String, String>): Response {
        val service = MonitorService.instance
            ?: return json("""{"ok":false,"error":"service_unavailable"}""")
        try {
            p["on"]?.let { service.setBabyMode(it.toBoolean()) }
            p["sensitivity"]?.toIntOrNull()?.let { service.setBabySensitivity(it) }
            p["roi"]?.let { raw ->
                val v = raw.split(",").map { it.trim().toFloatOrNull() }
                if (v.size == 4 && v.all { it != null && it in 0f..1f } &&
                    (v[2] ?: 0f) > 0f && (v[3] ?: 0f) > 0f
                ) {
                    state.motionRoi = v.joinToString(",") { "%.3f".format(it) }
                } else if (raw.equals("full", ignoreCase = true)) {
                    state.motionRoi = "0,0,1,1"
                } else {
                    return json("""{"ok":false,"error":"invalid_roi"}""")
                }
            }
        } catch (e: Exception) {
            return json("""{"ok":false,"error":"${e.message}"}""")
        }
        val roi = JSONArray()
        for (part in state.motionRoi.split(",")) roi.put(part.trim().toDouble())
        return json(
            JSONObject()
                .put("ok", true)
                .put("babyMonitorEnabled", state.babyMonitorEnabled)
                .put("babySensitivity", state.babySensitivity)
                .put("motionRoi", roi)
                .put("babyMotionActive", service.isBabyMotionActive())
                .toString()
        )
    }

    private fun apiCalibration(p: Map<String, String>): Response {
        val service = MonitorService.instance
            ?: return json("""{"ok":false,"error":"service_unavailable"}""")
        val action = p["action"]?.lowercase()
        val accepted = when (action) {
            null, "status" -> true
            "ambient" -> service.startAmbientCalibration()
            "adult" -> service.startAdultCalibration()
            "cancel" -> {
                service.cancelSceneCalibration()
                true
            }
            "reset" -> {
                service.resetSceneCalibration()
                true
            }
            else -> return json("""{"ok":false,"error":"invalid_action"}""")
        }
        return json(
            calibrationJson(service)
                .put("ok", accepted)
                .apply {
                    if (!accepted) put("error", "guardian_or_detector_unavailable")
                }
                .toString()
        )
    }

    private fun calibrationJson(service: MonitorService): JSONObject {
        val cry = service.cryCalibrationStatus()
        val adult = service.adultCalibrationStatus()
        return JSONObject()
            .put(
                "ambient",
                JSONObject()
                    .put("state", cry.state)
                    .put("startedAt", cry.startedAt)
                    .put("calibratedAt", cry.calibratedAt)
                    .put("sampleCount", cry.sampleCount)
                    .put("targetSamples", cry.targetSamples)
                    .put("noiseRms", cry.noiseRms)
                    .put("noiseScore", cry.noiseScore)
                    .put("silenceGate", service.crySilenceRmsGate())
                    .put("effectiveThreshold", service.cryThreshold())
            )
            .put(
                "adult",
                JSONObject()
                    .put("state", adult.state)
                    .put("startedAt", adult.startedAt)
                    .put("calibratedAt", adult.calibratedAt)
                    .put("sampleCount", adult.sampleCount)
                    .put("targetSamples", adult.targetSamples)
                    .put("currentPersonCount", adult.currentPersonCount)
                    .put("medianHeight", adult.medianHeight)
                    .put("medianArea", adult.medianArea)
                    .put("minHeight", adult.minHeight)
                    .put("minArea", adult.minArea)
            )
    }

    private fun apiInfo(): Response {
        val id = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return json(
            JSONObject()
                .put("ok", true)
                .put("name", "AIUniBOX-E11 Edge Camera")
                .put("model", android.os.Build.MODEL)
                .put("deviceId", id)
                .put("apiVersion", 5)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("authRequired", security.isEnabled)
                .toString()
        )
    }

    private fun apiStatus(): Response {
        val ip = NetworkUtil.getIpAddress(context)
        val encoder = streamer.h264Encoder
        val service = MonitorService.instance
        val leds = JSONObject()
        for (name in LedController.allLeds) leds.put(name, LedController.getLed(name))
        val lastBabyEvent: Any = service?.babyLastEventAt() ?: JSONObject.NULL
        val babyEvents = JSONArray()
        for ((ts, kind) in service?.babyRecentEvents() ?: emptyList()) {
            babyEvents.put(JSONObject().put("t", ts).put("k", kind))
        }
        val motionRoi = JSONArray()
        for (part in state.motionRoi.split(",")) motionRoi.put(part.trim().toDouble())
        val personBoxes = JSONArray()
        for (box in service?.personBoxes() ?: emptyList()) {
            personBoxes.put(JSONArray(box.toList()))
        }
        val clipStorage = service?.guardianClipStorageInfo()
        return json(JSONObject()
            .put("ok", true)
            .put("ip", ip)
            .put("cameraOn", streamer.isRunning)
            .put("streamReady", encoder?.isOutputHealthy() == true)
            .put("rtspPublishing", service?.isRtspPublishing() == true)
            .put("encodedFrames", encoder?.outputFrameCount ?: 0)
            .put("frameCapturedAt", streamer.latestJpegCapturedAtMs)
            .put("motorBusy", motor.isBusy())
            .put("uptimeSeconds", service?.uptimeSeconds() ?: 0)
            .put("watching", service?.isWatching() == true)
            .put("privacy", state.privacyMode)
            .put("positionSteps", state.positionSteps())
            .put("positionDegrees", state.positionDegrees())
            .put("presets", JSONArray(state.presetNames()))
            .put("sceneMode", state.sceneMode)
            .put("ircut", state.ircutEnabled)
            .put("fillLight", state.fillLightLevel)
            .put("leds", leds)
            .put("babyMonitorEnabled", state.babyMonitorEnabled)
            .put("babyMotionActive", service?.isBabyMotionActive() == true)
            .put("babyCryActive", service?.isBabyCryActive() == true)
            .put("cryModelLoaded", service?.isCryModelLoaded() == true)
            .put("cryAudioRms", service?.cryAudioRms() ?: 0f)
            .put("cryScore", service?.cryScore() ?: 0f)
            .put("cryThreshold", service?.cryThreshold() ?: 0f)
            .put("crySilenceRmsGate", service?.crySilenceRmsGate() ?: 0f)
            .put("cryTopClassIndex", service?.cryTopClassIndex() ?: -1)
            .put("cryTopClassScore", service?.cryTopClassScore() ?: 0f)
            .put("cryAnalyzedAt", service?.cryAnalyzedAt() ?: 0L)
            .put("npuModelLoaded", service?.isNpuModelLoaded() == true)
            .put("npuAnalyzedAt", service?.npuAnalyzedAt() ?: 0L)
            .put("npuSourceCapturedAt", service?.npuSourceCapturedAt() ?: 0L)
            .put("npuInferenceMs", service?.npuInferenceMs() ?: 0L)
            .put("npuAlertRefreshStatus", service?.npuAlertRefreshStatus() ?: "unavailable")
            .put("npuAlertRefreshAt", service?.npuAlertRefreshAt() ?: 0L)
            .put("personPresent", service?.isPersonPresent() == true)
            .put("personConfidence", service?.personConfidence() ?: 0f)
            .put("personBox", JSONArray(service?.personBox()?.toList() ?: emptyList<Float>()))
            .put("personBoxes", personBoxes)
            .put("personCount", service?.personCount() ?: 0)
            .put("adultLikePersonCount", service?.adultLikePersonCount() ?: 0)
            .put("caregiverEvidenceNow", service?.caregiverEvidenceNow() == true)
            .put("supervisionState", service?.supervisionState() ?: "unknown")
            .put("notificationsArmed", service?.notificationsArmed() != false)
            .put("lastSuppressedAlertAt", service?.lastSuppressedAlertAt() ?: 0L)
            .put("babySensitivity", state.babySensitivity)
            .put("babyLastEventAt", lastBabyEvent)
            .put("babyRecentEvents", babyEvents)
            .put("motionRoi", motionRoi)
            .put("barkConfigured", service?.isBarkConfigured() == true)
            .put("guardianEventCount", service?.guardianEvents(200)?.size ?: 0)
            .put("guardianClipCount", clipStorage?.clipCount ?: 0)
            .put("guardianClipBytes", clipStorage?.totalBytes ?: 0L)
            .apply { service?.let { put("sceneCalibration", calibrationJson(it)) } }
            .toString()
        )
    }

    private fun apiHealth(): Response {
        val summary = MonitorService.instance?.healthSummary()
            ?: return json("""{"ok":false,"error":"health_not_ready"}""")
        val coverageMinutes = if (summary.sampleCount <= 0) 0L else {
            ((summary.lastSampleAt - summary.firstSampleAt).coerceAtLeast(0L) / 60_000L) + 1L
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("windowHours", 24)
                .put("sampleIntervalSeconds", 60)
                .put("sampleCount", summary.sampleCount)
                .put("coverageMinutes", coverageMinutes)
                .put("firstSampleAt", summary.firstSampleAt)
                .put("lastSampleAt", summary.lastSampleAt)
                .putNullableMetric("cameraAvailabilityPercent", summary.cameraAvailabilityPercent)
                .putNullableMetric("streamAvailabilityPercent", summary.streamAvailabilityPercent)
                .put("cameraFreezeEpisodes", summary.cameraFreezeEpisodes)
                .putNullableMetric("averageEncodedFps", summary.averageEncodedFps)
                .putNullableMetric("minimumEncodedFps", summary.minimumEncodedFps)
                .putNullableMetric("npuAvailabilityPercent", summary.npuAvailabilityPercent)
                .putNullableMetric("averageNpuInferenceMs", summary.averageNpuInferenceMs)
                .put("maximumNpuInferenceMs", summary.maximumNpuInferenceMs)
                .putNullableMetric("audioAvailabilityPercent", summary.audioAvailabilityPercent)
                .put("serviceStartCount", summary.serviceStartCount)
                .put("additionalRestartCount", summary.additionalRestartCount)
                .put("freeStorageBytes", summary.freeStorageBytes)
                .put("minimumFreeStorageBytes", summary.minimumFreeStorageBytes)
                .put("freeStorageDeltaBytes", summary.freeStorageDeltaBytes)
                .put("clipStorageBytes", summary.clipStorageBytes)
                .put("clipStorageDeltaBytes", summary.clipStorageDeltaBytes)
                .put("currentIssues", JSONArray(summary.currentIssues))
                .put("thermalStatus", summary.thermalStatus)
                .put("thermalNote", "当前设备 Thermal HAL 不可用，健康判断不依赖温度数据")
                .toString()
        )
    }

    private fun JSONObject.putNullableMetric(key: String, value: Float): JSONObject =
        put(key, if (value < 0f) JSONObject.NULL else value.toDouble())

    /** 可解释事件时间线，以及端侧短片的逐条删除和一键清理入口。 */
    private fun apiEvents(p: Map<String, String>): Response {
        val service = MonitorService.instance
            ?: return json("""{"ok":false,"error":"service_unavailable"}""")
        p["deleteClip"]?.let { eventId ->
            if (!service.deleteGuardianClip(eventId)) {
                return json("""{"ok":false,"error":"event_or_clip_not_found"}""")
            }
        }
        p["feedback"]?.let { eventId ->
            val value = p["value"] ?: return json("""{"ok":false,"error":"feedback_value_required"}""")
            if (!service.setGuardianEventFeedback(eventId, value)) {
                return json("""{"ok":false,"error":"event_or_feedback_invalid"}""")
            }
        }
        val cleanup = if (p["clearClips"]?.toBoolean() == true) {
            service.clearGuardianHistoryClips()
        } else {
            null
        }
        if (p["testClip"]?.toBoolean() == true && !service.createGuardianTestClip()) {
            return json("""{"ok":false,"error":"clip_recorder_unavailable"}""")
        }
        val limit = (p["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 100)
        val items = JSONArray()
        for (event in service.guardianEvents(limit)) {
            val observations = JSONArray()
            for (observation in event.observations) {
                observations.put(
                    JSONObject()
                        .put("occurredAt", observation.occurredAt)
                        .put("kind", observation.kind)
                        .put("summary", observation.summary)
                        .put("decision", observation.decision)
                        .put("reason", observation.reason)
                        .put("personCount", observation.personCount)
                        .put("adultLikePersonCount", observation.adultLikePersonCount)
                        .put("supervisionState", observation.supervisionState)
                        .put("cryScore", observation.cryScore ?: JSONObject.NULL)
                        .put("visionRefreshStatus", observation.visionRefreshStatus)
                        .put("visionAnalyzedAt", observation.visionAnalyzedAt)
                        .put("visionSourceCapturedAt", observation.visionSourceCapturedAt)
                        .put("visionInferenceMs", observation.visionInferenceMs)
                )
            }
            items.put(
                JSONObject()
                    .put("id", event.id)
                    .put("occurredAt", event.occurredAt)
                    .put("lastOccurredAt", event.lastOccurredAt)
                    .put("kind", event.kind)
                    .put("title", event.title)
                    .put("summary", event.summary)
                    .put("decision", event.decision)
                    .put("reason", event.reason)
                    .put("personCount", event.personCount)
                    .put("adultLikePersonCount", event.adultLikePersonCount)
                    .put("supervisionState", event.supervisionState)
                    .put("cryScore", event.cryScore ?: JSONObject.NULL)
                    .put("motionCount", event.motionCount)
                    .put("cryCount", event.cryCount)
                    .put("notificationSent", event.notificationSent)
                    .put("observations", observations)
                    .put("feedback", event.feedback ?: JSONObject.NULL)
                    .put("feedbackAt", event.feedbackAt)
                    .put("clipStatus", event.clipStatus)
                    .put("clipDurationMs", event.clipDurationMs)
                    .put("clipSizeBytes", event.clipSizeBytes)
                    .put("clipError", event.clipError ?: JSONObject.NULL)
            )
        }
        val storage = service.guardianClipStorageInfo()
        return json(
            JSONObject()
                .put("ok", true)
                .put("events", items)
                .apply {
                    cleanup?.let {
                        put(
                            "cleanup",
                            JSONObject()
                                .put("deletedCount", it.deletedCount)
                                .put("failedCount", it.failedCount)
                                .put("freedBytes", it.freedBytes)
                        )
                    }
                }
                .put(
                    "storage",
                    JSONObject()
                        .put("location", "device_internal")
                        .put("clipCount", storage.clipCount)
                        .put("totalBytes", storage.totalBytes)
                        .put("maxClipCount", storage.maxClipCount)
                        .put("maxBytes", storage.maxBytes)
                        .put("preRollSeconds", 10)
                        .put("postRollSeconds", 20)
                        .put("includesAudio", false)
                )
                .toString()
        )
    }

    /**
     * Safari/iOS 播放 MP4 会先发 Range 请求；只返回指定区间，避免把整段视频读进内存。
     */
    private fun serveEventClip(eventId: String?, rangeHeader: String?): Response {
        if (eventId.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "缺少事件 ID")
        }
        val file = MonitorService.instance?.guardianClipFile(eventId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "短片不存在")
        val total = file.length()
        if (total <= 0L) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "短片为空")
        }

        var start = 0L
        var end = total - 1L
        var partial = false
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
            val parts = range.split('-', limit = 2)
            try {
                if (parts.firstOrNull().isNullOrBlank()) {
                    val suffix = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1L) ?: total
                    start = (total - suffix).coerceAtLeast(0L)
                } else {
                    start = parts[0].toLong().coerceAtLeast(0L)
                    end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1L) ?: end
                }
                partial = true
            } catch (_: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "无效 Range")
            }
        }
        if (start >= total || end < start) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "无效 Range")
        }

        val length = end - start + 1L
        val source = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            val amount = source.skip(start - skipped)
            if (amount <= 0L) break
            skipped += amount
        }
        val limited = object : InputStream() {
            private var remaining = length

            override fun read(): Int {
                if (remaining <= 0L) return -1
                val value = source.read()
                if (value >= 0) remaining--
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0L) return -1
                val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                if (count > 0) remaining -= count.toLong()
                return count
            }

            override fun close() = source.close()
        }
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, "video/mp4", limited, length).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Disposition", "inline; filename=guardian-event.mp4")
            addHeader("Cache-Control", "no-store")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$total")
        }
    }

    /** 通知设备管理：添加/删除/启停/测试 Bark 推送目标。 */
    private fun apiNotify(p: Map<String, String>): Response {
        val service = MonitorService.instance
            ?: return json("""{"ok":false,"error":"service_unavailable"}""")
        var opError: String? = null

        p["add"]?.let { key ->
            if (!service.barkAdd(p["name"] ?: "", key)) opError = "invalid_or_duplicate_key"
        }
        p["remove"]?.let { service.barkRemove(it) }
        p["toggle"]?.let { key ->
            service.barkSetEnabled(key, p["on"]?.toBoolean() ?: true)
        }
        p["test"]?.let { target ->
            if (target == "all" || target == "true") {
                if (!service.isBarkConfigured()) opError = "no_enabled_target"
                else service.sendTestBark(null)
            } else {
                service.sendTestBark(target)
            }
        }

        val targets = JSONArray()
        for (t in service.barkTargets()) {
            targets.put(
                JSONObject()
                    .put("name", t.name)
                    .put("key", t.key)
                    .put("enabled", t.enabled)
            )
        }
        return json(
            JSONObject()
                .put("ok", opError == null)
                .put("barkConfigured", service.isBarkConfigured())
                .put("targets", targets)
                .apply { opError?.let { put("error", it) } }
                .toString()
        )
    }

    /** 硬件动作会整幅改变画面，通知动作检测丢弃参考帧。 */
    private fun invalidateMotionReference() {
        MonitorService.instance?.invalidateMotionReference()
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun unauthorized(): Response =
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            """{"ok":false,"error":"unauthorized"}"""
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("WWW-Authenticate", "Bearer")
        }
}
