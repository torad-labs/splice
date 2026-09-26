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

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/** Deliberately tiny so the overflow path is reached in a handful of events, not thousands. */
private const val TEST_BACKLOG = 4
private const val OVERFLOW_BY = 50

/** The production sizes (EventBus.kt DEFAULT_BACKLOG and REPLAY_RING), restated because the resume
 *  defect only exists where the ring is LARGER than the backlog. */
private const val PRODUCTION_BACKLOG = 256
private const val REPLAY_RING_SIZE = 512

/** The review's failing input: more than a backlog behind, still inside the ring. */
private const val FAR_BEHIND = 300

/** A bound on the interleaving wait, never a timing: both exits are events (B finished, B blocked). */
private const val INTERLEAVE_DEADLINE_NS = 10_000_000_000L

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

    /** V4-126 review: the replay used to be written into a fresh BACKLOG-sized channel before the
     *  route started draining it, so a console resuming from far behind got the first 256 of the
     *  ring and the rest were dropped — counted on a number that never reaches the wire. 300 against
     *  a 256 backlog and a 512 ring is the review's own failing input. */
    @Test
    fun `a resume from far behind receives every event after its id, and keeps its live backlog`() {
        val bus = EventBus(PRODUCTION_BACKLOG)
        repeat(FAR_BEHIND) { bus.publish { seq -> event(seq) } }

        val resumed = bus.subscribe(lastEventId = 0)
        // The replay must not eat the live headroom: a full backlog of NEW events published before
        // the route drains anything must still fit.
        repeat(PRODUCTION_BACKLOG) { bus.publish { seq -> event(seq) } }

        val received = generateSequence { resumed.channel.tryReceive().getOrNull() }.map { it.seq }.toList()
        assertEquals(0L, resumed.dropped, "a resume inside the ring drops nothing")
        assertEquals((1L..(FAR_BEHIND + PRODUCTION_BACKLOG).toLong()).toList(), received)
    }

    /** The documented contract for an id the ring no longer covers (FEATURES.md §6: "an id older than
     *  the ring replays the whole ring"): everything the ring holds, in order, and the first id the
     *  client sees is past its own + 1 — the gap is ON THE WIRE, never silent. */
    @Test
    fun `a resume older than the ring replays the whole ring and shows the gap in its first id`() {
        val bus = EventBus(PRODUCTION_BACKLOG)
        val total = REPLAY_RING_SIZE + FAR_BEHIND
        repeat(total) { bus.publish { seq -> event(seq) } }

        val resumed = bus.subscribe(lastEventId = 0)
        val received = generateSequence { resumed.channel.tryReceive().getOrNull() }.map { it.seq }.toList()

        assertEquals(((total - REPLAY_RING_SIZE + 1).toLong()..total.toLong()).toList(), received)
        assertEquals(0L, resumed.dropped, "the whole ring fits the resumed subscriber")
        assertTrue(received.first() > 1L, "the first id past lastEventId + 1 is how the client learns it fell behind")
    }

    /** V4-126 review: the seq was taken OUTSIDE the lock, so two publishers could append in the
     *  opposite order to the one their ids were minted in — a client then sees id 2 before id 1 and
     *  its Last-Event-ID resume skips an event. Deterministic, no timing: publisher A mints its id
     *  and parks inside its build until publisher B has either FINISHED (ids minted outside the
     *  lock: B appends first, the stream reads 2,1) or is BLOCKED on the bus (ids minted inside it:
     *  B cannot append until A has). */
    @Test
    fun `ids reach a subscriber strictly increasing even when two publishers interleave`() {
        val bus = EventBus(PRODUCTION_BACKLOG)
        val live = bus.subscribe()
        val aInBuild = CountDownLatch(1)
        val bDone = CountDownLatch(1)
        lateinit var b: Thread

        // B is still between minting and appending: neither finished nor parked on the bus.
        fun bInFlight() = bDone.count > 0 && b.state != Thread.State.BLOCKED
        val a = Thread {
            bus.publish { seq ->
                aInBuild.countDown()
                val deadline = System.nanoTime() + INTERLEAVE_DEADLINE_NS
                while (bInFlight() && System.nanoTime() < deadline) Thread.onSpinWait()
                event(seq)
            }
        }
        b = Thread {
            aInBuild.await()
            bus.publish { seq -> event(seq) }
            bDone.countDown()
        }
        b.start()
        a.start()
        a.join()
        b.join()

        val order = generateSequence { live.channel.tryReceive().getOrNull() }.map { it.seq }.toList()
        assertEquals(listOf(1L, 2L), order, "the wire must carry ids in the order they were minted")
        val replay = bus.subscribe(lastEventId = 0)
        val ring = generateSequence { replay.channel.tryReceive().getOrNull() }.map { it.seq }.toList()
        assertEquals(listOf(1L, 2L), ring, "the replay ring must hold them in the same order")
    }

    @Test
    @OptIn(DelicateCoroutinesApi::class) // isClosedForReceive: the closed state IS the assertion
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
