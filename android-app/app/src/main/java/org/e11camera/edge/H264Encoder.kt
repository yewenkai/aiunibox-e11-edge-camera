package org.e11camera.edge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long
)

/**
 * H.264 硬件编码器（MediaCodec + Surface 输入，零拷贝）。
 *
 * 使用 createInputSurface()，Camera2 直接写入编码器的 Surface，
 * 完全不经过 CPU 的 YUV 转换。这是 Android 上最高效的编码方式。
 */
class H264Encoder(
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = "video/avc"
        private const val BITRATE = 800_000    // 800Kbps
        private const val FPS = 30             // 目标 30fps
        private const val I_INTERVAL = 2       // 每2秒一个 I 帧
        private const val MAX_QUEUE = 10   // 小队列=低延迟
    }

    private var codec: MediaCodec? = null
    @Volatile private var running = false

    /** 编码后的 H.264 NAL 队列（主缓冲，drainAll 消费） */
    private val nalQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE)

    /**
     * 多客户端订阅：每个连接 /h264 的客户端注册一个独立队列，
     * 编码器把每帧同时分发给所有订阅者。
     */
    private val subscribers =
        java.util.concurrent.ConcurrentHashMap.newKeySet<LinkedBlockingQueue<EncodedFrame>>()

    /** 注册一个新的流订阅者（返回它的独立队列） */
    fun subscribe(): LinkedBlockingQueue<EncodedFrame> {
        val q = LinkedBlockingQueue<EncodedFrame>(MAX_QUEUE * 2)
        subscribers.add(q)
        return q
    }

    /** 注销订阅 */
    fun unsubscribe(q: LinkedBlockingQueue<EncodedFrame>) {
        subscribers.remove(q)
    }

    @Volatile var hasSpsPps = false
        private set
    private var spsPps: ByteArray = ByteArray(0)

    @Volatile
    var outputFrameCount: Long = 0
        private set

    @Volatile
    var lastOutputAtMs: Long = 0
        private set

    /** 编码器的输入 Surface（Camera2 直接写入） */
    var inputSurface: Surface? = null
        private set

    fun start() {
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_INTERVAL)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // 创建输入 Surface（零拷贝路径）
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            running = true
            Thread { drainOutput() }.start()
            Log.i(TAG, "H.264 Surface 编码器启动 ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "编码器启动失败", e)
        }
    }

    /** 读取编码输出 */
    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val c = codec ?: break
                val idx = c.dequeueOutputBuffer(info, 10000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(data, 0, info.size)

                            // 检测 SPS/PPS
                            if (data.size > 4) {
                                val nalType = data[4].toInt() and 0x1f
                                if (nalType == 7) {
                                    spsPps = data
                                    hasSpsPps = true
                                }
                            }

                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig) {
                                outputFrameCount++
                                lastOutputAtMs = System.currentTimeMillis()
                                // 写入主队列（向后兼容）
                                while (!nalQueue.offer(data)) {
                                    nalQueue.poll()
                                }
                                // 分发给所有订阅者（多客户端模式）
                                val frame = EncodedFrame(data, info.presentationTimeUs)
                                for (sub in subscribers) {
                                    while (!sub.offer(frame)) {
                                        sub.poll()
                                    }
                                }
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "编码格式变更: ${c.outputFormat}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "drainOutput 异常", e)
            }
        }
    }

    fun drainAll(): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        nalQueue.drainTo(result)
        return result
    }

    fun getSpsPps(): ByteArray = spsPps

    fun available(): Int = nalQueue.size

    fun isOutputHealthy(maxSilenceMs: Long = 5000): Boolean =
        hasSpsPps && lastOutputAtMs > 0 &&
            System.currentTimeMillis() - lastOutputAtMs <= maxSilenceMs

    /** 请求关键帧（场景变化时调用） */
    fun requestKeyFrame() {
        val c = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        inputSurface = null
        nalQueue.clear()
        Log.i(TAG, "H.264 编码器停止")
    }
}
