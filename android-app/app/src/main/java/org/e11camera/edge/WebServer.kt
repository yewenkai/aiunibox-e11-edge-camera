package org.e11camera.edge

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
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
 *  GET /api/status       状态 JSON
 */
class WebServer(
    private val context: Context,
    private val streamer: CameraStreamer,
    private val motor: MotorController,
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
        Log.i(TAG, "请求: ${session.method} $uri params=$params")
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveIndex()
                uri == "/stream" -> serveMjpeg()
                uri == "/snapshot" -> serveSnapshot()
                uri == "/h264" -> serveH264()
                uri.startsWith("/api/motor") -> apiMotor(params)
                uri.startsWith("/api/led") -> apiLed(params)
                uri.startsWith("/api/alloff") -> apiAllOff()
                uri.startsWith("/api/filllight") -> apiFillLight(params)
                uri.startsWith("/api/ircut") -> apiIrcut(params)
                uri.startsWith("/api/scene") -> apiScene(params)
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
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
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
                val accepted = motor.turnLeft(steps, speed)
                return json("""{"ok":$accepted,"action":"left","steps":$steps,"speed":$speed,"busy":${motor.isBusy()}${if (accepted) "" else ",\"error\":\"motor_busy\""}}""")
            }
            "right" -> {
                val steps = (p["steps"] ?: "83").toIntOrNull() ?: 83
                val accepted = motor.turnRight(steps, speed)
                return json("""{"ok":$accepted,"action":"right","steps":$steps,"speed":$speed,"busy":${motor.isBusy()}${if (accepted) "" else ",\"error\":\"motor_busy\""}}""")
            }
            "reset" -> {
                // 厂商 -R 是硬件归零流程，不是普通居中；该设备上会持续励磁锁住电机。
                return json("""{"ok":false,"action":"reset","error":"hardware_reset_disabled"}""")
            }
            else -> return json("""{"ok":false,"error":"unknown dir: $action"}""")
        }
    }

    private fun apiLed(p: Map<String, String>): Response {
        val name = p["name"] ?: "ir"
        val level = (p["level"] ?: "0").toIntOrNull() ?: 0
        if (!LedController.setLed(name, level)) {
            return json("""{"ok":false,"error":"unsupported_led"}""")
        }
        return json("""{"ok":true,"led":"$name","level":$level}""")
    }

    private fun apiAllOff(): Response {
        LedController.allOff()
        return json("""{"ok":true,"action":"all_off"}""")
    }

    private fun apiFillLight(p: Map<String, String>): Response {
        val level = (p["level"] ?: "0").toIntOrNull() ?: 0
        LedController.setFillLight(level)
        return json("""{"ok":true,"filllight":$level}""")
    }

    private fun apiIrcut(p: Map<String, String>): Response {
        val on = p["on"]?.toBoolean() ?: false
        LedController.setIrcut(on)
        return json("""{"ok":true,"ircut":${if (on) "night" else "day"}}""")
    }

    private fun apiScene(p: Map<String, String>): Response {
        val mode = (p["mode"] ?: "0").toIntOrNull() ?: 0
        LedController.setSceneMode(mode)
        val names = arrayOf("day", "night", "auto")
        return json("""{"ok":true,"scene":"${names[mode.coerceIn(0, 2)]}"}""")
    }

    private fun apiStatus(): Response {
        val ip = NetworkUtil.getIpAddress(context)
        val encoder = streamer.h264Encoder
        val service = MonitorService.instance
        return json(
            """{"ok":true,"ip":"$ip","cameraOn":${streamer.isRunning},"streamReady":${encoder?.isOutputHealthy() == true},"rtspPublishing":${service?.isRtspPublishing() == true},"encodedFrames":${encoder?.outputFrameCount ?: 0},"motorBusy":${motor.isBusy()},"uptimeSeconds":${service?.uptimeSeconds() ?: 0}}"""
        )
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s).apply {
            addHeader("Cache-Control", "no-store")
        }
}
