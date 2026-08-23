package org.e11camera.edge

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧事件短片录制器。
 *
 * 它订阅现有 MediaCodec H.264 输出，不再启动一套摄像头或编码器。内存中常驻最近
 * 10 秒编码帧；动作/哭声/可选姿态事件发生后继续收集 20 秒并封装为 MP4。短片包含已经烧进
 * 画面的东八区时间戳，当前版本为纯视频，不额外占用麦克风。
 */
class EventClipRecorder(
    private val store: GuardianEventStore
) {
    companion object {
        private const val TAG = "EventClipRecorder"
        private const val PRE_ROLL_MS = 10_000L
        private const val POST_ROLL_MS = 20_000L
        private const val MAX_RING_BYTES = 4 * 1024 * 1024
        private const val MAX_ACTIVE_CLIPS = 4
    }

    private data class BufferedFrame(
        val frame: EncodedFrame,
        val receivedAtMs: Long
    )

    private data class PendingClip(
        val eventId: String,
        var finishAtMs: Long,
        val frames: MutableList<EncodedFrame>
    )

    private val running = AtomicBoolean(false)
    private val lock = Any()
    private val ring = ArrayDeque<BufferedFrame>()
    private val active = mutableListOf<PendingClip>()
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var encoder: H264Encoder? = null
    @Volatile private var queue: LinkedBlockingQueue<EncodedFrame>? = null
    @Volatile private var readerThread: Thread? = null
    private var ringBytes = 0

    @Synchronized
    fun start(newEncoder: H264Encoder?) {
        if (newEncoder == null || running.get()) return
        encoder = newEncoder
        queue = newEncoder.subscribe()
        running.set(true)
        readerThread = Thread({ readLoop() }, "event-clip-reader").apply { start() }
        Log.i(TAG, "事件短片缓存已启动（前 ${PRE_ROLL_MS / 1000}s / 后 ${POST_ROLL_MS / 1000}s）")
    }

    /** 返回 false 表示当前没有可用的视频编码链路。 */
    fun capture(eventId: String): Boolean {
        val currentEncoder = encoder
        if (!running.get() || currentEncoder == null) {
            store.markClipFailed(eventId, "视频编码链路不可用")
            return false
        }
        synchronized(lock) {
            if (active.size >= MAX_ACTIVE_CLIPS) {
                store.markClipFailed(eventId, "同时录制的事件过多")
                return false
            }
            val buffered = ring.map { it.frame }
            val firstKeyFrame = buffered.indexOfFirst { it.isKeyFrame() }
            val initial = if (firstKeyFrame >= 0) {
                buffered.drop(firstKeyFrame).toMutableList()
            } else {
                mutableListOf()
            }
            active.add(
                PendingClip(
                    eventId = eventId,
                    finishAtMs = System.currentTimeMillis() + POST_ROLL_MS,
                    frames = initial
                )
            )
        }
        currentEncoder.requestKeyFrame()
        return true
    }

    /** 同一事件会话再次触发时，将尚未结束的短片延长到最近信号后 20 秒。 */
    fun extend(eventId: String): Boolean = synchronized(lock) {
        val pending = active.firstOrNull { it.eventId == eventId } ?: return@synchronized false
        pending.finishAtMs = maxOf(pending.finishAtMs, System.currentTimeMillis() + POST_ROLL_MS)
        true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        readerThread?.interrupt()
        readerThread = null
        val oldQueue = queue
        queue = null
        oldQueue?.let { encoder?.unsubscribe(it) }
        val pending = synchronized(lock) {
            val copy = active.toList()
            active.clear()
            ring.clear()
            ringBytes = 0
            copy
        }
        pending.forEach { submitWrite(it) }
        encoder = null
        Log.i(TAG, "事件短片缓存已停止")
    }

    fun release() {
        stop()
        writer.shutdown()
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val frame = queue?.poll(500, TimeUnit.MILLISECONDS)
                if (frame != null) onFrame(frame)
                finalizeCompleted(System.currentTimeMillis())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.w(TAG, "读取编码帧异常", e)
            }
        }
    }

    private fun onFrame(frame: EncodedFrame) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ring.addLast(BufferedFrame(frame, now))
            ringBytes += frame.data.size
            while (ring.isNotEmpty() &&
                (now - ring.first().receivedAtMs > PRE_ROLL_MS || ringBytes > MAX_RING_BYTES)
            ) {
                ringBytes -= ring.removeFirst().frame.data.size
            }

            for (clip in active) {
                // 没有可用的历史关键帧时，从事件后的第一个关键帧开始。
                if (clip.frames.isEmpty() && !frame.isKeyFrame()) continue
                clip.frames.add(frame)
            }
        }
    }

    private fun finalizeCompleted(now: Long) {
        val completed = synchronized(lock) {
            val ready = active.filter { now >= it.finishAtMs }
            active.removeAll(ready.toSet())
            ready
        }
        completed.forEach { submitWrite(it) }
    }

    private fun submitWrite(clip: PendingClip, format: MediaFormat? = encoder?.getEncodedFormat()) {
        writer.execute {
            val usable = clip.frames.dropWhile { !it.isKeyFrame() }
            if (format == null || usable.isEmpty()) {
                store.markClipFailed(clip.eventId, "没有取得可封装的关键帧")
                return@execute
            }

            val target = store.newClipFile(clip.eventId)
            val temp = File(target.parentFile, "${target.name}.tmp")
            var muxer: MediaMuxer? = null
            try {
                target.parentFile?.mkdirs()
                temp.delete()
                muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(format)
                muxer.start()
                val firstPts = usable.first().presentationTimeUs
                var lastPts = firstPts
                for (frame in usable) {
                    if ((frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue
                    val pts = (frame.presentationTimeUs - firstPts).coerceAtLeast(0L)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, frame.data.size, pts, frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv())
                    }
                    muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), info)
                    lastPts = frame.presentationTimeUs
                }
                muxer.stop()
                muxer.release()
                muxer = null
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                if (!target.isFile || target.length() <= 0L) {
                    throw IllegalStateException("短片文件为空")
                }
                val durationMs = ((lastPts - firstPts) / 1000L).coerceAtLeast(0L)
                store.markClipReady(clip.eventId, target, durationMs)
                Log.i(TAG, "事件短片已保存 id=${clip.eventId} duration=${durationMs}ms bytes=${target.length()}")
            } catch (e: Exception) {
                Log.w(TAG, "保存事件短片失败 id=${clip.eventId}: ${e.message}", e)
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                temp.delete()
                target.delete()
                store.markClipFailed(clip.eventId, e.message ?: "MP4 封装失败")
            }
        }
    }

    private fun EncodedFrame.isKeyFrame(): Boolean =
        (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
}
