// NEW: V4-166 — a llama-server's slot count is read behind the turns, never on their path, each change
// in what the runtime answers is logged once, and every head on one runtime shares one slot table.
package campaign.v4166

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import splice.app.provider.RuntimeSlotCount
import splice.app.provider.SlotTables
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.upstream.local.LlamaServerSlots
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeSlotCountTest {

    private val logged = CopyOnWriteArrayList<String>()

    /** Reads run inline, so each refresh has finished when the call that started it returns. */
    private val inline = RuntimeSlotCount.Background(
        CoroutineScope(Dispatchers.Unconfined),
        LogSink { logged += it },
        Dispatchers.Unconfined,
    )

    // Mutant: read /props inside read() (V4-165's shape). The turn waits on a runtime that is slow to
    // answer, on a call thread that holds a process-wide materialization permit.
    @Test
    fun `a turn never waits on the runtime`() {
        val answered = CountDownLatch(1)
        val http = LocalHttp { _, _, _ ->
            answered.await(WAIT_S, TimeUnit.SECONDS)
            LocalHttpReply(200, """{"total_slots":4}""")
        }
        val background = RuntimeSlotCount.Background(
            CoroutineScope(Dispatchers.IO),
            LogSink { logged += it },
            Dispatchers.IO,
        )
        val count = RuntimeSlotCount("bonsai", LlamaServerSlots(URL, http), background)
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { assertNull(count.read(), "not known yet: unpinned") }
        } finally {
            answered.countDown()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (count.read() == null && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(4, count.read())
    }

    // Mutants: log every reading (four lines, three of them noise), or never re-read (the -np change
    // is never seen and the last read() still says 4).
    @Test
    fun `a stale answer is read again, and each change is logged once`() {
        val replies = ArrayDeque(
            listOf(
                LocalHttpReply(200, """{"total_slots":4}"""),
                LocalHttpReply(200, """{"total_slots":4}"""),
                LocalHttpReply(401, """{"error":{"code":401,"message":"Invalid API Key"}}"""),
                LocalHttpReply(200, """{"total_slots":2}"""),
            ),
        )
        var now = 0L
        val http = LocalHttp { _, _, _ -> replies.removeFirst() }
        val count = RuntimeSlotCount("bonsai", LlamaServerSlots(URL, http), inline, ElapsedClock { now })

        assertEquals(4, count.read())
        assertEquals(4, count.read(), "fresh: no second read")
        now = STALE
        assertEquals(4, count.read())
        now += STALE
        assertNull(count.read(), "a 401 pauses slot affinity: unpinned")
        now += STALE
        assertEquals(2, count.read())

        assertEquals(
            listOf(
                "[bonsai] slot affinity: the runtime runs 4 slots; each conversation keeps its own\n",
                "[bonsai] slot affinity paused: http://127.0.0.1:8099/props answered HTTP 401 without a slot " +
                    "count — turns go out unpinned until it answers with a slot count\n",
                "[bonsai] slot affinity: the runtime runs 2 slots; each conversation keeps its own\n",
            ),
            logged,
        )
    }

    // Mutant: build a table per call (V4-165's per-head construction). Two heads on one llama-server
    // each think slot 0 is free and pin two conversations to it; a topology reload does the same to
    // the turns still in flight.
    @Test
    fun `every head on one runtime shares one table, and a reload finds it again`() {
        val tables = SlotTables(inline)
        val http = LocalHttp { _, _, _ -> LocalHttpReply(200, """{"total_slots":2}""") }
        val headA = tables.forRuntime("bonsai", URL, null, http)
        val headB = tables.forRuntime("bonsai-fast", "$URL/", null, http)

        assertSame(headA, headB)
        assertNotEquals(headA.lease("conv-a")!!.slot, headB.lease("conv-b")!!.slot)
        assertNotSame(headA, tables.forRuntime("other", "http://127.0.0.1:8100/v1", null, http))
        assertNotSame(headA, tables.forRuntime("bonsai", URL, "rotated-key", http), "a new bearer, a new reader")
    }
}

private const val URL = "http://127.0.0.1:8099/v1"

// why: comfortably past RuntimeSlotCount's 10 s refresh, so a step of it always reads again.
private const val STALE = 10_001L

// why: an upper bound for a background read that answers at once; the test does not wait for it
// unless the code under test is broken.
private const val WAIT_S = 5L
