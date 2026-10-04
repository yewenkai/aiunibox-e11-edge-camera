package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDemandQueueTest {
    @Test
    fun `requests waiting before submission share one frame`() {
        val queue = SnapshotDemandQueue()
        val first = queue.enqueue(1_000)
        val second = queue.enqueue(2_000)

        assertEquals(second, queue.claim(100))
        assertEquals(second, queue.activeSequence)
        assertNull(queue.claim(101))
        assertTrue(queue.complete(second))
        assertTrue(queue.isCompleted(first))
        assertTrue(queue.isCompleted(second))
        assertFalse(queue.isPending(first))
        assertFalse(queue.hasDemand(102))
        assertNull(queue.activeSequence)
    }

    @Test
    fun `request arriving after submission needs another frame`() {
        val queue = SnapshotDemandQueue()
        val first = queue.enqueue(1_000)
        assertEquals(first, queue.claim(10))
        val late = queue.enqueue(1_000)

        assertTrue(queue.complete(first))
        assertTrue(queue.isCompleted(first))
        assertFalse(queue.isCompleted(late))
        assertTrue(queue.isPending(late))
        assertEquals(late, queue.claim(20))
        assertTrue(queue.complete(late))
        assertTrue(queue.isCompleted(late))
    }

    @Test
    fun `expired requests are not captured or marked completed by a later frame`() {
        val queue = SnapshotDemandQueue()
        val expired = queue.enqueue(100)
        val valid = queue.enqueue(101)

        assertEquals(valid, queue.claim(100))
        assertFalse(queue.isPending(expired))
        assertTrue(queue.complete(valid))
        assertFalse(queue.isCompleted(expired))
        assertTrue(queue.isCompleted(valid))
    }

    @Test
    fun `only expired demand leaves no capture to submit`() {
        val queue = SnapshotDemandQueue()
        val request = queue.enqueue(100)

        assertFalse(queue.hasDemand(100))
        assertNull(queue.claim(100))
        assertFalse(queue.isPending(request))
        assertFalse(queue.isCompleted(request))
    }

    @Test
    fun `expiry during an active capture does not release its flight`() {
        val queue = SnapshotDemandQueue()
        val first = queue.enqueue(100)
        assertEquals(first, queue.claim(10))
        assertFalse(queue.hasDemand(100))
        val next = queue.enqueue(1_000)

        assertNull(queue.claim(101))
        assertEquals(first, queue.activeSequence)
        assertTrue(queue.complete(first))
        assertFalse(queue.isCompleted(first))
        assertTrue(queue.isPending(next))
        assertEquals(next, queue.claim(102))
    }

    @Test
    fun `release retries remaining demand and rejects an old completion`() {
        val queue = SnapshotDemandQueue()
        val first = queue.enqueue(1_000)
        assertEquals(first, queue.claim(10))
        val late = queue.enqueue(2_000)
        queue.release(first)

        assertFalse(queue.complete(first))
        assertEquals(late, queue.claim(20))
        assertFalse(queue.complete(first))
        queue.release(first)
        assertEquals(late, queue.activeSequence)
        assertTrue(queue.complete(late))
        assertTrue(queue.isCompleted(first))
        assertTrue(queue.isCompleted(late))
    }

    @Test
    fun `released demand can be retried after its expired peers are removed`() {
        val queue = SnapshotDemandQueue()
        val expired = queue.enqueue(50)
        val valid = queue.enqueue(500)
        assertEquals(valid, queue.claim(10))
        queue.release(valid)

        assertEquals(valid, queue.claim(50))
        assertTrue(queue.complete(valid))
        assertFalse(queue.isCompleted(expired))
        assertTrue(queue.isCompleted(valid))
    }

    @Test
    fun `clear cancels waiters and stale callbacks cannot alter the next batch`() {
        val queue = SnapshotDemandQueue()
        val old = queue.enqueue(1_000)
        assertEquals(old, queue.claim(10))
        queue.clear()
        val current = queue.enqueue(2_000)

        assertTrue(current > old)
        assertFalse(queue.isPending(old))
        assertFalse(queue.isCompleted(old))
        assertEquals(current, queue.claim(20))
        assertFalse(queue.complete(old))
        queue.release(old)
        assertEquals(current, queue.activeSequence)
        assertTrue(queue.complete(current))
        assertFalse(queue.isCompleted(old))
        assertTrue(queue.isCompleted(current))
    }

    @Test
    fun `clear also discards completion records`() {
        val queue = SnapshotDemandQueue()
        val request = queue.enqueue(1_000)
        assertEquals(request, queue.claim(10))
        assertTrue(queue.complete(request))

        queue.clear()

        assertFalse(queue.isCompleted(request))
        assertFalse(queue.hasDemand(11))
        assertNull(queue.activeSequence)
        assertTrue(queue.enqueue(2_000) > request)
    }

    @Test
    fun `many callers cannot create concurrent flights or complete late demand early`() {
        val queue = SnapshotDemandQueue()
        val first = queue.enqueue(10_000)
        assertEquals(first, queue.claim(0))
        val later = (1..1_000).map {
            queue.enqueue(10_000).also { assertNull(queue.claim(it)) }
        }

        assertEquals(first, queue.activeSequence)
        assertTrue(queue.complete(first))
        later.forEach { assertFalse(queue.isCompleted(it)) }
        val final = later.last()
        assertEquals(final, queue.claim(1_001))
        assertNull(queue.claim(1_002))
        assertTrue(queue.complete(final))
        later.forEach { assertTrue(queue.isCompleted(it)) }
        assertFalse(queue.complete(final))
        assertFalse(queue.hasDemand(1_003))
    }

    @Test
    fun `cancelled request is not completed along with its remaining batch`() {
        val queue = SnapshotDemandQueue()
        val cancelled = queue.enqueue(1_000)
        val remaining = queue.enqueue(1_000)
        assertEquals(remaining, queue.claim(10))
        queue.cancel(cancelled)

        assertTrue(queue.complete(remaining))
        assertFalse(queue.isPending(cancelled))
        assertFalse(queue.isCompleted(cancelled))
        assertTrue(queue.isCompleted(remaining))
        queue.cancel(remaining)
        assertFalse(queue.isCompleted(remaining))
    }

    @Test
    fun `cancelling the final demand keeps an existing capture reserved until released`() {
        val queue = SnapshotDemandQueue()
        val request = queue.enqueue(1_000)
        assertEquals(request, queue.claim(10))
        queue.cancel(request)

        assertFalse(queue.hasDemand(11))
        assertNull(queue.claim(11))
        assertEquals(request, queue.activeSequence)
        queue.release(request)
        assertNull(queue.activeSequence)
        assertNull(queue.claim(12))
        assertFalse(queue.complete(request))
        assertFalse(queue.isCompleted(request))
    }
}
