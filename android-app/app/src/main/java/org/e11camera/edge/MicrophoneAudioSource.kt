package org.e11camera.edge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 设备麦克风的唯一采集入口。
 *
 * 哭声模型和网页双向语音共享同一个 AudioRecord，避免两路采集在
 * 部分 Android 音频 HAL 上互相抢占。不做持久化，订阅者为空时立即释放麦克风。
 */
class MicrophoneAudioSource(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val CHUNK_SAMPLES = 320 // 20 ms
        private const val TAG = "MicrophoneAudio"
    }

    class Subscription internal constructor(
        private val closeAction: () -> Unit
    ) : Closeable {
        @Volatile private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            closeAction()
        }
    }

    private val lock = Any()
    private val nextId = AtomicLong(1)
    private val listeners = ConcurrentHashMap<Long, (ShortArray) -> Unit>()

    @Volatile private var captureRequested = false
    @Volatile private var shuttingDown = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null

    @Volatile var lastCapturedAtMs: Long = 0
        private set

    @Volatile var lastError: String = ""
        private set

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val isRunning: Boolean
        get() = captureRequested && captureThread?.isAlive == true

    fun subscribe(name: String, listener: (ShortArray) -> Unit): Subscription? {
        if (!hasPermission) {
            lastError = "missing_record_audio_permission"
            Log.w(TAG, "缺少 RECORD_AUDIO 权限，无法启动订阅: $name")
            return null
        }
        val id = nextId.getAndIncrement()
        synchronized(lock) {
            if (shuttingDown) return null
            listeners[id] = listener
            if (captureThread?.isAlive != true) startCaptureLocked()
        }
        return Subscription { unsubscribe(id) }
    }

    private fun unsubscribe(id: Long) {
        synchronized(lock) {
            listeners.remove(id)
            if (listeners.isEmpty()) stopCaptureLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCaptureLocked() {
        if (captureRequested || listeners.isEmpty() || shuttingDown) return
        captureRequested = true
        lastError = ""
        captureThread = Thread({ captureLoop() }, "e11-microphone").apply {
            start()
        }
    }

    private fun stopCaptureLocked() {
        captureRequested = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        captureThread?.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var record: AudioRecord? = null
        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "无法设置麦克风线程优先级，继续采集", e)
            }
            val minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBytes <= 0) error("invalid_min_buffer_$minimumBytes")
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBytes, CHUNK_SAMPLES * 2 * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                error("audio_record_not_initialized")
            }
            audioRecord = record
            record.startRecording()
            Log.i(TAG, "共享麦克风已启动（16kHz PCM16 单声道）")

            while (captureRequested && !Thread.currentThread().isInterrupted) {
                val chunk = ShortArray(CHUNK_SAMPLES)
                var offset = 0
                while (offset < chunk.size && captureRequested) {
                    val count = record.read(
                        chunk,
                        offset,
                        chunk.size - offset,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count < 0) error("audio_read_$count")
                    if (count == 0) continue
                    offset += count
                }
                if (offset <= 0) continue
                val payload = if (offset == chunk.size) chunk else chunk.copyOf(offset)
                lastCapturedAtMs = System.currentTimeMillis()
                for (callback in listeners.values) {
                    try {
                        callback(payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "麦克风订阅回调异常", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (captureRequested) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "共享麦克风异常退出: $lastError", e)
            }
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            val shouldRetry = synchronized(lock) {
                val retry = captureRequested && listeners.isNotEmpty() && !shuttingDown
                if (audioRecord === record) audioRecord = null
                if (captureThread === Thread.currentThread()) captureThread = null
                captureRequested = false
                retry
            }
            Log.i(TAG, "共享麦克风已停止")
            if (shouldRetry) {
                Thread({
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                    }
                    synchronized(lock) {
                        if (!shuttingDown && listeners.isNotEmpty() && captureThread?.isAlive != true) {
                            startCaptureLocked()
                        }
                    }
                }, "e11-microphone-retry").start()
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            shuttingDown = true
            listeners.clear()
            stopCaptureLocked()
        }
    }
}
