// NEW: V4-126 — the backpressure wall: one slow console must never slow a turn.
//
// The row asked for a load test rather than a review comment, because this property is invisible in
// normal use and catastrophic when it breaks: the daemon publishes from the turn path, so if
// publishing could wait on a subscriber, a browser tab that stops reading would apply backpressure
// to the head and stall live turns. The impossible-to-miss form of the guarantee is that publish()
// is NOT a suspend function — it cannot await anything — so what this test proves is the other half:
// that the overflow path actually engages, that it is COUNTED rather than silent, and that a
// subscriber which IS draining loses nothing.
package splice.events.bus

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Deliberately tiny so the overflow path is reached in a handful of events, not thousands. */
private const val TEST_BACKLOG = 4
private const val OVERFLOW_BY = 50

class EventBusBackpressureTest {

    private fun event(seq: Long) = ConsoleEvent.TurnStart(seq, "claude", "session-1")

    @Test
    fun `a subscriber that stops draining drops and counts, and publishing never waits`() {
        val bus = EventBus(TEST_BACKLOG)
        val slow = bus.subscribe()
        val total = TEST_BACKLOG + OVERFLOW_BY

        // If publish() could block, this loop would never finish: nothing drains `slow`.
        repeat(total) { bus.publish { seq -> event(seq) } }

        assertEquals(OVERFLOW_BY.toLong(), slow.dropped, "the gap must be counted, not silent")
        val held = generateSequence { slow.channel.tryReceive().getOrNull() }.count()
        assertEquals(TEST_BACKLOG, held, "the backlog holds exactly its capacity and no more")
    }

    @Test
    fun `a subscriber that keeps up receives every event in order with no drops`() = runBlocking {
        val bus = EventBus(TEST_BACKLOG)
        val fast = bus.subscribe()
        val total = TEST_BACKLOG + OVERFLOW_BY

        // DRAIN BETWEEN PUBLISHES, which is what a console that keeps up actually does, and what
        // makes this deterministic rather than a race between a tight publish loop and a collector
        // coroutine. The backlog stays at TEST_BACKLOG, so this also pins that completeness does not
        // depend on a big buffer — the first version of this test published all `total` events
        // before reading any, so the "draining" subscriber overflowed exactly like the slow one and
        // the test failed for the reason it was written to rule out.
        val received = buildList {
            repeat(total) {
                bus.publish { seq -> event(seq) }
                add(fast.channel.receive())
            }
        }
        assertEquals(total, received.size, "a subscriber that keeps up must lose nothing")
        assertEquals(total.toLong(), received.last().seq, "the last event published is the last received")
        assertEquals(received.map { it.seq }.sorted(), received.map { it.seq }, "order must be preserved")
        assertEquals(0L, fast.dropped)
    }

    @Test
    fun `a late subscriber replays only what it missed`() {
        val bus = EventBus(TEST_BACKLOG)
        val ids = (1..5).map { bus.publish { seq -> event(seq) }.seq }
        val resumed = bus.subscribe(lastEventId = ids[2])
        val replayed = generateSequence { resumed.channel.tryReceive().getOrNull() }.map { it.seq }.toList()
        assertEquals(listOf(ids[3], ids[4]), replayed, "resume must be strictly after the given id")
    }

    @Test
    fun `unsubscribing stops delivery and releases the subscriber`() {
        val bus = EventBus(TEST_BACKLOG)
        val subscription = bus.subscribe()
        assertEquals(1, bus.subscriberCount)
        bus.unsubscribe(subscription)
        assertEquals(0, bus.subscriberCount)
        bus.publish { seq -> event(seq) }
        assertTrue(subscription.channel.isClosedForReceive, "an unsubscribed channel is closed")
    }
}
