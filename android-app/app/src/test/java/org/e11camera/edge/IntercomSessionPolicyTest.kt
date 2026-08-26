package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomSessionPolicyTest {
    @Test
    fun `multiple listeners are allowed but talk is exclusive`() {
        val policy = IntercomSessionPolicy()
        policy.register("mom")
        policy.register("dad")

        assertTrue(policy.setListening("mom", true))
        assertTrue(policy.setListening("dad", true))
        assertEquals(2, policy.snapshot().listeners)
        assertEquals(IntercomSessionPolicy.TalkResult.GRANTED, policy.beginTalk("mom"))
        assertEquals(IntercomSessionPolicy.TalkResult.BUSY, policy.beginTalk("dad"))
        assertEquals("mom", policy.snapshot().talkOwnerId)
    }

    @Test
    fun `disconnect releases talk ownership and listener`() {
        val policy = IntercomSessionPolicy()
        policy.register("browser")
        policy.setListening("browser", true)
        policy.beginTalk("browser")

        assertTrue(policy.unregister("browser"))
        assertEquals(0, policy.snapshot().connectedClients)
        assertEquals(0, policy.snapshot().listeners)
        assertNull(policy.snapshot().talkOwnerId)
    }

    @Test
    fun `unknown peer cannot control a session`() {
        val policy = IntercomSessionPolicy()

        assertFalse(policy.setListening("unknown", true))
        assertEquals(
            IntercomSessionPolicy.TalkResult.UNKNOWN_PEER,
            policy.beginTalk("unknown")
        )
    }

    @Test
    fun `only current owner can be released after playback failure`() {
        val policy = IntercomSessionPolicy()
        policy.register("mom")
        policy.register("dad")
        policy.beginTalk("mom")

        assertFalse(policy.endTalk("dad"))
        assertEquals("mom", policy.snapshot().talkOwnerId)
        assertTrue(policy.endTalk("mom"))
        assertEquals(IntercomSessionPolicy.TalkResult.GRANTED, policy.beginTalk("dad"))
    }
}
