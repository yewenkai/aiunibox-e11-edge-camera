package org.e11camera.edge

/** 双向语音会话的纯状态策略：多人可监听，同一时刻只允许一人说话。 */
class IntercomSessionPolicy {
    enum class TalkResult { GRANTED, ALREADY_OWNER, BUSY, UNKNOWN_PEER }

    data class Snapshot(
        val connectedClients: Int,
        val listeners: Int,
        val talkOwnerId: String?
    )

    private val peers = linkedSetOf<String>()
    private val listeners = linkedSetOf<String>()
    private var talkOwnerId: String? = null

    @Synchronized
    fun register(peerId: String) {
        peers += peerId
    }

    @Synchronized
    fun unregister(peerId: String): Boolean {
        peers -= peerId
        listeners -= peerId
        val ownedTalk = talkOwnerId == peerId
        if (ownedTalk) talkOwnerId = null
        return ownedTalk
    }

    @Synchronized
    fun setListening(peerId: String, enabled: Boolean): Boolean {
        if (peerId !in peers) return false
        if (enabled) listeners += peerId else listeners -= peerId
        return true
    }

    @Synchronized
    fun beginTalk(peerId: String): TalkResult {
        if (peerId !in peers) return TalkResult.UNKNOWN_PEER
        if (talkOwnerId == peerId) return TalkResult.ALREADY_OWNER
        if (talkOwnerId != null) return TalkResult.BUSY
        talkOwnerId = peerId
        return TalkResult.GRANTED
    }

    @Synchronized
    fun endTalk(peerId: String): Boolean {
        if (talkOwnerId != peerId) return false
        talkOwnerId = null
        return true
    }

    @Synchronized
    fun isTalkOwner(peerId: String): Boolean = talkOwnerId == peerId

    @Synchronized
    fun talkActive(): Boolean = talkOwnerId != null

    @Synchronized
    fun listenerIds(): Set<String> = listeners.toSet()

    @Synchronized
    fun clearListeners(): Set<String> {
        val removed = listeners.toSet()
        listeners.clear()
        return removed
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(peers.size, listeners.size, talkOwnerId)
}
