package org.e11camera.edge

import android.media.MediaCodec
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 RTSP 推流器：直接把 MediaCodec 的 H.264 输出通过 RTP/UDP 推到 mediamtx。
 * 砍掉 ffmpeg 中间环节，降低延迟和抖动。
 *
 * 工作流程：
 * 1. TCP 连接 mediamtx RTSP 端口，ANNOUNCE + RECORD
 * 2. 编码器的每帧 H.264 → RTP 打包 → UDP 发送
 *
 * 这是极简实现，只支持单路 H.264 视频。
 */
class RtspPublisher(
    private val encoder: H264Encoder,
    private val host: String = "127.0.0.1",
    private val port: Int = 8554,
    private val path: String = "cam"
) {
    companion object {
        private const val TAG = "RtspPublisher"
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = AtomicBoolean(false)
    @Volatile private var subscribed = false
    @Volatile
    var connected = false
        private set

    fun start() {
        if (running.get()) return
        running.set(true)
        executor.submit { publishLoop() }
        Log.i(TAG, "RTSP 推流器启动 → $host:$port/$path")
    }

    private fun publishLoop() {
        var retryDelay = 1000L
        while (running.get()) {
            try {
                publishOnce()
                retryDelay = 1000L
            } catch (e: Exception) {
                val hadActiveSession = connected
                connected = false
                if (hadActiveSession) {
                    retryDelay = 1000L
                }
                Log.w(TAG, "推流中断: ${e.message}，${retryDelay}ms 后重试")
                if (subscribed) {
                    subscriberQueue?.let { encoder.unsubscribe(it) }
                    subscriberQueue = null
                    subscribed = false
                }
                Thread.sleep(retryDelay)
                retryDelay = minOf(retryDelay * 2, 10000)
            }
        }
    }

    private var subscriberQueue: java.util.concurrent.LinkedBlockingQueue<EncodedFrame>? = null

    private fun publishOnce() {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 10000
        val os = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        // 等 SPS/PPS
        var wait = 0
        while (!encoder.hasSpsPps && wait < 100) {
            Thread.sleep(50); wait++
        }
        val spsPps = encoder.getSpsPps()
        if (spsPps.isEmpty()) throw Exception("无 SPS/PPS")

        // 解析 SPS（提取 sps 用于 SDP）
        val sps = extractNal(spsPps, 7) ?: throw Exception("无 SPS")
        val pps = extractNal(spsPps, 8)
        val spsB64 = base64Encode(sps)
        val ppsB64 = if (pps != null) base64Encode(pps) else ""

        // 构建 SDP
        val sdp = buildSdp(spsB64, ppsB64)

        // RTSP 握手
        var cseq = 1
        // OPTIONS
        sendRtsp(os, "OPTIONS rtsp://$host:$port/$path RTSP/1.0", cseq++, "")
        val optionsResp = readResponse(reader)
        ensureOk("OPTIONS", optionsResp)
        Log.d(TAG, "OPTIONS resp: ${optionsResp.take(100)}")
        // ANNOUNCE
        sendRtsp(os, "ANNOUNCE rtsp://$host:$port/$path RTSP/1.0", cseq++,
            "Content-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        val announceResp = readResponse(reader)
        ensureOk("ANNOUNCE", announceResp)
        Log.d(TAG, "ANNOUNCE resp: ${announceResp.take(100)}")
        // SETUP（TCP interleaved 模式）
        val setupResp = sendAndRead(os, reader, cseq++,
            "SETUP rtsp://$host:$port/$path/streamid=0 RTSP/1.0",
            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record")
        ensureOk("SETUP", setupResp)
        Log.d(TAG, "SETUP resp: $setupResp")
        val session = extractHeader(setupResp, "Session")?.split(";")?.get(0)
            ?: throw Exception("无 Session: $setupResp")
        Log.i(TAG, "RTSP 会话建立 session=$session")

        // RECORD
        sendRtsp(os, "RECORD rtsp://$host:$port/$path RTSP/1.0", cseq++,
            "Session: $session\r\nRange: npt=0.000-")
        val recordResp = readResponse(reader)
        ensureOk("RECORD", recordResp)
        connected = true

        // gortsplib 会通过同一 TCP 连接发送 RTCP Receiver Report。
        // 持续排空输入，避免长时间运行后接收缓冲区占满。
        socket.soTimeout = 0
        Thread({
            val buf = ByteArray(2048)
            try {
                val input = socket.getInputStream()
                while (running.get() && !socket.isClosed) {
                    if (input.read(buf) < 0) break
                }
            } catch (_: Exception) {
            }
        }, "rtsp-rtcp-reader").apply {
            isDaemon = true
            start()
        }

        // 订阅编码器输出
        subscriberQueue = encoder.subscribe()
        subscribed = true

        // TCP interleaved 模式：RTP 通过 RTSP TCP 连接发送
        // 格式: '$' + channel(1B) + length(2B big-endian) + RTP data
        val rtpPacker = RtpPacker()
        val rtpChannel = 0 // video RTP channel

        fun sendInterleaved(data: ByteArray) {
            synchronized(os) {
                val header = ByteArray(4)
                header[0] = '$'.code.toByte()
                header[1] = rtpChannel.toByte()
                header[2] = ((data.size ushr 8) and 0xff).toByte()
                header[3] = (data.size and 0xff).toByte()
                os.write(header)
                os.write(data)
                os.flush()
            }
        }

        var frameCount = 0
        var fpsTime = System.currentTimeMillis()

        while (running.get()) {
            val frame = subscriberQueue?.poll(100, TimeUnit.MILLISECONDS) ?: continue
            for (pkt in rtpPacker.packAccessUnit(frame.data, frame.presentationTimeUs)) {
                sendInterleaved(pkt)
            }

            frameCount++
            val now = System.currentTimeMillis()
            if (now - fpsTime > 5000) {
                val fps = frameCount * 1000.0 / (now - fpsTime)
                Log.i(TAG, "推流帧率: ${"%.1f".format(fps)} fps")
                frameCount = 0
                fpsTime = now
            }
        }

        // TEARDOWN
        try {
            sendRtsp(os, "TEARDOWN rtsp://$host:$port/$path RTSP/1.0", cseq++,
                "Session: $session")
        } catch (_: Exception) {}
        socket.close()
        connected = false
        if (subscribed) {
            subscriberQueue?.let { encoder.unsubscribe(it) }
            subscriberQueue = null
            subscribed = false
        }
    }

    private fun buildSdp(spsB64: String, ppsB64: String): String {
        return "v=0\r\n" +
            "o=- 0 0 IN IP4 $host\r\n" +
            "s=AIUniBOXE11EdgeCamera\r\n" +
            "c=IN IP4 $host\r\n" +
            "t=0 0\r\n" +
            "m=video 0 RTP/AVP 96\r\n" +
            "a=control:streamid=0\r\n" +
            "a=sendonly\r\n" +
            "a=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1; sprop-parameter-sets=$spsB64${if (ppsB64.isNotEmpty()) ",$ppsB64" else ""}\r\n"
    }

    private fun sendRtsp(os: OutputStream, request: String, cseq: Int, extra: String) {
        val msg = buildString {
            append(request).append("\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("User-Agent: AIUniBOXE11EdgeCamera\r\n")
            when {
                extra.isEmpty() -> append("\r\n")
                // extra 已包含“头部 + 空行 + 正文”，正文后不能再附加空请求。
                extra.contains("\r\n\r\n") -> append(extra)
                else -> append(extra).append("\r\n\r\n")
            }
        }
        os.write(msg.toByteArray())
        os.flush()
    }

    private fun sendAndRead(os: OutputStream, reader: BufferedReader, cseq: Int, request: String, extra: String): String {
        sendRtsp(os, request, cseq, extra)
        return readResponse(reader)
    }

    private fun readResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            sb.append(line).append("\r\n")
            if (line.isEmpty()) break
            line = reader.readLine()
        }
        return sb.toString()
    }

    private fun extractHeader(resp: String, name: String): String? {
        for (line in resp.split("\r\n")) {
            if (line.startsWith("$name:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    private fun ensureOk(method: String, response: String) {
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 200 ")) {
            throw Exception("$method 失败: ${response.trim()}")
        }
    }

    private fun extractServerPort(resp: String): Int? {
        val transport = extractHeader(resp, "Transport") ?: return null
        val m = Regex("server_port=(\\d+)-").find(transport)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 从含多个 NAL 的 buffer 中提取指定类型的 NAL */
    private fun extractNal(data: ByteArray, nalType: Int): ByteArray? {
        var idx = 0
        while (idx < data.size) {
            val start = findStartCode(data, idx)
            if (start < 0) break
            val end = findStartCode(data, start + 4)
            val codeSize = if (start + 3 < data.size && data[start + 2] == 1.toByte()) 3 else 4
            val nalStart = start + codeSize
            val nalData = if (end > 0) data.copyOfRange(nalStart, end)
            else data.copyOfRange(nalStart, data.size)
            if (nalData.isNotEmpty() && (nalData[0].toInt() and 0x1f) == nalType) {
                return nalData
            }
            idx = if (end > 0) end else data.size
        }
        return null
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 4) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    /** Base64 编码（Android 自带） */
    private fun base64Encode(data: ByteArray): String {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    }

    fun stop() {
        running.set(false)
        connected = false
        if (subscribed) {
            subscriberQueue?.let { encoder.unsubscribe(it) }
            subscriberQueue = null
            subscribed = false
        }
        executor.shutdownNow()
        Log.i(TAG, "RTSP 推流器停止")
    }
}

/**
 * RTP 打包器：把 H.264 NAL 单元打包成 RTP 包（RFC 6184）
 */
class RtpPacker {
    private var seqNum = 0
    private var ssrc = (Math.random() * 0xFFFFFFFF).toLong().toInt()

    fun packAccessUnit(accessUnit: ByteArray, presentationTimeUs: Long): List<ByteArray> {
        val pts = presentationTimeUs * 90L / 1000L
        val results = ArrayList<ByteArray>()
        val nals = splitAnnexB(accessUnit)
        for ((index, nalData) in nals.withIndex()) {
            packNal(nalData, pts, index == nals.lastIndex, results)
        }
        return results
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(Pair(i, 3))
                    i += 3
                    continue
                }
                if (i <= data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(Pair(i, 4))
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)

        val result = ArrayList<ByteArray>()
        for (index in starts.indices) {
            val start = starts[index].first + starts[index].second
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            if (end > start) result.add(data.copyOfRange(start, end))
        }
        return result
    }

    private fun packNal(
        nalData: ByteArray,
        pts: Long,
        isLastNal: Boolean,
        results: MutableList<ByteArray>
    ) {
        if (nalData.isEmpty()) return
        val maxPayload = 1200 // MTU 友好

        if (nalData.size <= maxPayload) {
            // 单包模式
            results.add(makeRtpPacket(pts, nalData, isLastNal))
        } else {
            // FU-A 分片模式
            val nalHeader = nalData[0]
            val nalType = nalHeader.toInt() and 0x1f
            val nri = nalHeader.toInt() and 0x60
            var offset = 1 // 跳过原始 NAL header

            while (offset < nalData.size) {
                val chunkSize = minOf(maxPayload - 2, nalData.size - offset)
                val isLast = (offset + chunkSize >= nalData.size)

                val fu = ByteArray(chunkSize + 2)
                fu[0] = ((28 or nri) and 0xff).toByte() // FU indicator: type 28
                var fu2 = nalType.toByte().toInt()
                if (offset == 1) fu2 = fu2 or 0x80 // start bit
                if (isLast) fu2 = fu2 or 0x40 // end bit
                fu[1] = fu2.toByte()
                System.arraycopy(nalData, offset, fu, 2, chunkSize)

                results.add(makeRtpPacket(pts, fu, isLastNal && isLast))
                offset += chunkSize
            }
        }
    }

    private fun makeRtpPacket(pts: Long, payload: ByteArray, mark: Boolean): ByteArray {
        val packet = ByteArray(12 + payload.size)
        // RTP header
        packet[0] = 0x80.toByte() // V=2
        packet[1] = ((96 or if (mark) 0x80 else 0) and 0xff).toByte() // PT=96, marker
        // sequence
        packet[2] = ((seqNum ushr 8) and 0xff).toByte()
        packet[3] = (seqNum and 0xff).toByte()
        seqNum = (seqNum + 1) and 0xffff
        // timestamp
        val ts = pts.toInt()
        packet[4] = ((ts ushr 24) and 0xff).toByte()
        packet[5] = ((ts ushr 16) and 0xff).toByte()
        packet[6] = ((ts ushr 8) and 0xff).toByte()
        packet[7] = (ts and 0xff).toByte()
        // SSRC
        packet[8] = ((ssrc ushr 24) and 0xff).toByte()
        packet[9] = ((ssrc ushr 16) and 0xff).toByte()
        packet[10] = ((ssrc ushr 8) and 0xff).toByte()
        packet[11] = (ssrc and 0xff).toByte()
        // payload
        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }
}
