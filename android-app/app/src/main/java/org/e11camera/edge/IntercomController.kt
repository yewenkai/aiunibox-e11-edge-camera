package org.e11camera.edge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class IntercomStatus(
    val connectedClients: Int = 0,
    val listeners: Int = 0,
    val talkActive: Boolean = false,
    val microphoneAvailable: Boolean = false,
    val microphoneRunning: Boolean = false,
    val microphoneBlockedByPrivacy: Boolean = false,
    val crySuppressedByPlayback: Boolean = false,
    val lastDeviceAudioAt: Long = 0,
    val lastBrowserAudioAt: Long = 0,
    val droppedUplinkFrames: Long = 0,
    val droppedDownlinkFrames: Long = 0,
    val lastError: String = ""
)

/**
 * 局域网双向语音协调器。网络层只传输 16kHz/单声道/PCM16LE：
 *
 * - 多个页面可同时监听设备麦克风；
 * - 同一时刻只有一个页面可按住说话；
 * - 说话期间暂停麦克风下行，并抑制哭声检测，降低回声误报。
 */
class IntercomController(
    private val microphone: MicrophoneAudioSource,
    private val isMicrophoneBlocked: () -> Boolean
) {
    interface Peer {
        val id: String
        fun sendText(payload: String)
        fun sendAudio(payload: ByteArray)
    }

    companion object {
        private const val TAG = "Intercom"
        private const val MAX_BROWSER_FRAME_BYTES = 16 * 1024
        private const val PLAYBACK_CRY_COOLDOWN_MS = 1_500L
    }

    private val peers = ConcurrentHashMap<String, Peer>()
    private val sessions = IntercomSessionPolicy()
    private val playback = DeviceAudioPlayer(::handlePlaybackFailure)
    private val microphoneFrames = ArrayBlockingQueue<ByteArray>(12)
    private val microphoneLock = Any()
    private val droppedDownlink = AtomicLong(0)

    @Volatile private var microphoneSubscription: MicrophoneAudioSource.Subscription? = null
    @Volatile private var active = true
    @Volatile private var playbackSuppressUntilMs = 0L
    @Volatile private var lastDeviceAudioAtMs = 0L
    @Volatile private var lastBrowserAudioAtMs = 0L
    @Volatile private var lastError = ""

    private val dispatcherThread = Thread({ dispatchMicrophoneLoop() }, "e11-intercom-downlink").apply {
        start()
    }

    fun register(peer: Peer) {
        peers[peer.id] = peer
        sessions.register(peer.id)
        sendStatus(peer)
        broadcastStatus()
    }

    fun unregister(peerId: String) {
        peers.remove(peerId)
        if (sessions.unregister(peerId)) stopPlayback()
        reconcileMicrophoneSubscription()
        broadcastStatus()
    }

    fun handleText(peerId: String, payload: String) {
        val peer = peers[peerId] ?: return
        try {
            val message = JSONObject(payload)
            when (message.optString("type")) {
                "listen" -> setListening(peer, message.optBoolean("active", false))
                "talk" -> setTalking(peer, message.optBoolean("active", false))
                "ping" -> peer.sendText(JSONObject().put("type", "pong").toString())
                else -> sendError(peer, "unsupported_message", "不支持的语音消息")
            }
        } catch (e: Exception) {
            sendError(peer, "invalid_message", "语音控制消息格式无效")
        }
    }

    fun handleAudio(peerId: String, payload: ByteArray) {
        val peer = peers[peerId] ?: return
        if (!sessions.isTalkOwner(peerId)) {
            sendError(peer, "not_talk_owner", "当前页面没有说话权")
            return
        }
        if (payload.isEmpty() || payload.size % 2 != 0 || payload.size > MAX_BROWSER_FRAME_BYTES) {
            sendError(peer, "invalid_audio_frame", "音频帧必须是 PCM16LE 且不超过 16KB")
            return
        }
        if (!playback.enqueue(payload.copyOf())) {
            lastError = "speaker_queue_full"
        }
        lastBrowserAudioAtMs = System.currentTimeMillis()
    }

    private fun setListening(peer: Peer, enabled: Boolean) {
        if (enabled && isMicrophoneBlocked()) {
            sessions.setListening(peer.id, false)
            sendError(peer, "privacy_mode", "隐私模式已禁用设备麦克风回传")
            sendStatus(peer)
            return
        }
        sessions.setListening(peer.id, enabled)
        if (enabled && !reconcileMicrophoneSubscription()) {
            sessions.setListening(peer.id, false)
            sendError(peer, "microphone_unavailable", "设备麦克风不可用，请检查 RECORD_AUDIO 权限")
        } else {
            reconcileMicrophoneSubscription()
        }
        broadcastStatus()
    }

    private fun setTalking(peer: Peer, enabled: Boolean) {
        if (!enabled) {
            if (sessions.endTalk(peer.id)) stopPlayback()
            broadcastStatus()
            return
        }
        when (sessions.beginTalk(peer.id)) {
            IntercomSessionPolicy.TalkResult.GRANTED -> {
                if (!playback.start(peer.id)) {
                    sessions.endTalk(peer.id)
                    lastError = playback.lastError
                    sendError(peer, "speaker_unavailable", "设备扬声器初始化失败")
                } else {
                    peer.sendText(JSONObject().put("type", "talk-granted").toString())
                }
            }
            IntercomSessionPolicy.TalkResult.ALREADY_OWNER ->
                peer.sendText(JSONObject().put("type", "talk-granted").toString())
            IntercomSessionPolicy.TalkResult.BUSY ->
                sendError(peer, "talk_busy", "另一个页面正在说话")
            IntercomSessionPolicy.TalkResult.UNKNOWN_PEER -> return
        }
        broadcastStatus()
    }

    private fun stopPlayback() {
        playback.stop()
        playbackSuppressUntilMs = System.currentTimeMillis() + PLAYBACK_CRY_COOLDOWN_MS
    }

    private fun handlePlaybackFailure(ownerId: String, error: String) {
        lastError = error
        playbackSuppressUntilMs = System.currentTimeMillis() + PLAYBACK_CRY_COOLDOWN_MS
        if (!sessions.endTalk(ownerId)) return
        peers[ownerId]?.let {
            sendError(it, "speaker_playback_failed", "设备扬声器播放中断，请松开后重试")
        }
        broadcastStatus()
    }

    /** 隐私模式变更时由 MonitorService 立即调用。 */
    fun onPrivacyChanged() {
        if (isMicrophoneBlocked()) {
            val removed = sessions.clearListeners()
            for (peerId in removed) {
                peers[peerId]?.let {
                    sendError(it, "privacy_mode", "隐私模式已禁用设备麦克风回传")
                }
            }
        }
        reconcileMicrophoneSubscription()
        broadcastStatus()
    }

    private fun reconcileMicrophoneSubscription(): Boolean {
        synchronized(microphoneLock) {
            val shouldCapture = active && sessions.snapshot().listeners > 0 && !isMicrophoneBlocked()
            if (!shouldCapture) {
                microphoneSubscription?.close()
                microphoneSubscription = null
                microphoneFrames.clear()
                return true
            }
            if (microphoneSubscription != null) return true
            microphoneSubscription = microphone.subscribe("intercom") { samples ->
                if (sessions.talkActive() || isMicrophoneBlocked()) return@subscribe
                val bytes = pcm16LittleEndian(samples)
                if (!microphoneFrames.offer(bytes)) {
                    microphoneFrames.poll()
                    droppedDownlink.incrementAndGet()
                    microphoneFrames.offer(bytes)
                }
            }
            if (microphoneSubscription == null) {
                lastError = microphone.lastError.ifBlank { "microphone_unavailable" }
                return false
            }
            return true
        }
    }

    private fun dispatchMicrophoneLoop() {
        while (active && !Thread.currentThread().isInterrupted) {
            val payload = try {
                microphoneFrames.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            if (sessions.talkActive() || isMicrophoneBlocked()) continue
            val deadPeers = mutableListOf<String>()
            for (peerId in sessions.listenerIds()) {
                val peer = peers[peerId] ?: continue
                try {
                    peer.sendAudio(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "设备麦克风回传失败 peer=$peerId", e)
                    deadPeers += peerId
                }
            }
            lastDeviceAudioAtMs = System.currentTimeMillis()
            for (peerId in deadPeers) unregister(peerId)
        }
    }

    fun isCrySuppressedByPlayback(now: Long = System.currentTimeMillis()): Boolean =
        sessions.talkActive() || now < playbackSuppressUntilMs

    fun status(): IntercomStatus {
        val snapshot = sessions.snapshot()
        return IntercomStatus(
            connectedClients = snapshot.connectedClients,
            listeners = snapshot.listeners,
            talkActive = snapshot.talkOwnerId != null,
            microphoneAvailable = microphone.hasPermission,
            microphoneRunning = microphone.isRunning,
            microphoneBlockedByPrivacy = isMicrophoneBlocked(),
            crySuppressedByPlayback = isCrySuppressedByPlayback(),
            lastDeviceAudioAt = lastDeviceAudioAtMs,
            lastBrowserAudioAt = lastBrowserAudioAtMs,
            droppedUplinkFrames = playback.droppedFrames,
            droppedDownlinkFrames = droppedDownlink.get(),
            lastError = lastError.ifBlank { playback.lastError.ifBlank { microphone.lastError } }
        )
    }

    private fun sendStatus(peer: Peer) {
        val current = status()
        val owner = sessions.isTalkOwner(peer.id)
        peer.sendText(
            JSONObject()
                .put("type", "status")
                .put("codec", "pcm_s16le")
                .put("sampleRate", MicrophoneAudioSource.SAMPLE_RATE)
                .put("channels", MicrophoneAudioSource.CHANNELS)
                .put("connectedClients", current.connectedClients)
                .put("listeners", current.listeners)
                .put("listening", peer.id in sessions.listenerIds())
                .put("talkActive", current.talkActive)
                .put("talkOwner", owner)
                .put("microphoneAvailable", current.microphoneAvailable)
                .put("microphoneBlockedByPrivacy", current.microphoneBlockedByPrivacy)
                .put("crySuppressedByPlayback", current.crySuppressedByPlayback)
                .toString()
        )
    }

    private fun broadcastStatus() {
        for (peer in peers.values) {
            try {
                sendStatus(peer)
            } catch (e: Exception) {
                Log.w(TAG, "发送语音状态失败 peer=${peer.id}", e)
            }
        }
    }

    private fun sendError(peer: Peer, code: String, message: String) {
        try {
            peer.sendText(
                JSONObject()
                    .put("type", "error")
                    .put("code", code)
                    .put("message", message)
                    .toString()
            )
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        active = false
        synchronized(microphoneLock) {
            microphoneSubscription?.close()
            microphoneSubscription = null
        }
        playback.stop()
        microphoneFrames.clear()
        dispatcherThread.interrupt()
        peers.clear()
    }

    private fun pcm16LittleEndian(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        var target = 0
        for (sample in samples) {
            val value = sample.toInt()
            bytes[target++] = (value and 0xff).toByte()
            bytes[target++] = ((value ushr 8) and 0xff).toByte()
        }
        return bytes
    }
}

/** AudioTrack 流式播放器，队列满时丢最旧帧，优先保证对讲实时性。 */
private class DeviceAudioPlayer(
    private val onFailure: (ownerId: String, error: String) -> Unit
) {
    companion object {
        private const val TAG = "IntercomPlayer"
    }

    private val queue = ArrayBlockingQueue<ByteArray>(40)
    private val lock = Any()

    @Volatile private var active = false
    @Volatile private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile var lastError: String = ""
        private set

    @Volatile var droppedFrames: Long = 0
        private set

    fun start(ownerId: String): Boolean = synchronized(lock) {
        if (active && track?.state == AudioTrack.STATE_INITIALIZED) return true
        stopLocked()
        return try {
            val minimumBytes = AudioTrack.getMinBufferSize(
                MicrophoneAudioSource.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBytes <= 0) error("invalid_min_buffer_$minimumBytes")
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MicrophoneAudioSource.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimumBytes, MicrophoneAudioSource.SAMPLE_RATE / 2))
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                error("audio_track_not_initialized")
            }
            queue.clear()
            track = created
            active = true
            created.play()
            thread = Thread({ playbackLoop(created, ownerId) }, "e11-intercom-speaker").apply { start() }
            lastError = ""
            Log.i(TAG, "设备扬声器对讲已启动")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "设备扬声器初始化失败: $lastError", e)
            stopLocked()
            false
        }
    }

    fun enqueue(payload: ByteArray): Boolean {
        if (!active) return false
        if (queue.offer(payload)) return true
        queue.poll()
        droppedFrames++
        return queue.offer(payload)
    }

    private fun playbackLoop(audioTrack: AudioTrack, ownerId: String) {
        var failure: String? = null
        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "无法设置扬声器线程优先级，继续播放", e)
            }
            while (active && !Thread.currentThread().isInterrupted) {
                val payload = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (offset < payload.size && active) {
                    val written = audioTrack.write(
                        payload,
                        offset,
                        payload.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written < 0) error("audio_track_write_$written")
                    if (written == 0) break
                    offset += written
                }
            }
        } catch (e: Exception) {
            if (active) {
                failure = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "设备扬声器播放异常: $failure", e)
            }
        } finally {
            var releaseCurrent = false
            var notifyFailure: String? = null
            synchronized(lock) {
                if (track === audioTrack) {
                    active = false
                    queue.clear()
                    track = null
                    thread = null
                    releaseCurrent = true
                    failure?.let {
                        lastError = it
                        notifyFailure = it
                    }
                }
            }
            if (releaseCurrent) releaseTrack(audioTrack)
            notifyFailure?.let { onFailure(ownerId, it) }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        active = false
        queue.clear()
        thread?.interrupt()
        thread = null
        val current = track
        track = null
        releaseTrack(current)
    }

    private fun releaseTrack(current: AudioTrack?) {
        try {
            current?.pause()
            current?.flush()
            current?.stop()
        } catch (_: Exception) {
        }
        try {
            current?.release()
        } catch (_: Exception) {
        }
    }
}
