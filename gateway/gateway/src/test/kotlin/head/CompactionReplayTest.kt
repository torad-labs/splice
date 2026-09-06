// NEW (2026-09-05): the store of detached compactions' answers — what a byte-identical retry finds.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.gateway.head.CompactionReplay
import splice.gateway.wire.FrameRecording

class CompactionReplayTest {

    private var now = 0L
    private val replay = CompactionReplay(clock = ElapsedClock { now }, ttlMs = 1_000, capacity = 2)

    private fun whole() = FrameRecording().apply {
        append("event: message_start\n\n")
        append("event: message_stop\n\n")
        complete()
    }

    @Test
    fun `the key is the session and the upstream bytes - no session, no key`() {
        assertNull(replay.key(null, "{}"))
        assertNull(replay.key(" ", "{}"))
        assertEquals(replay.key("s", """{"a":1}"""), replay.key("s", """{"a":1}"""))
        assertNotEquals(replay.key("s", """{"a":1}"""), replay.key("s", """{"a":2}"""))
        assertNotEquals(replay.key("s", "{}"), replay.key("t", "{}"))
    }

    @Test
    fun `an in-flight recording is found by a retry and only a kept one survives its finish`() {
        val key = checkNotNull(replay.key("s", "{}"))
        val inFlight = FrameRecording().apply { append("event: message_start\n\n") }
        replay.begin(key, inFlight)
        assertSame(inFlight, replay.lookup(key), "a retry attaches to the compaction still in flight")
        replay.finish(key, inFlight, keep = false) // the client stayed, or the ending was not clean
        assertNull(replay.lookup(key), "not kept: the retry runs upstream")
        val kept = whole()
        replay.begin(key, kept)
        replay.finish(key, kept, keep = true)
        assertSame(kept, replay.lookup(key))
        replay.consumed(key)
        assertNull(replay.lookup(key), "a delivered replay is spent")
    }

    @Test
    fun `a finish for a superseded recording leaves the newer one alone`() {
        val key = checkNotNull(replay.key("s", "{}"))
        val old = whole()
        val newer = whole()
        replay.begin(key, old)
        replay.begin(key, newer)
        replay.finish(key, old, keep = false)
        assertSame(newer, replay.lookup(key))
    }

    @Test
    fun `entries expire by age and the oldest goes first past capacity`() {
        val k1 = checkNotNull(replay.key("s", "1"))
        val k2 = checkNotNull(replay.key("s", "2"))
        val k3 = checkNotNull(replay.key("s", "3"))
        replay.begin(k1, whole())
        now = 500
        replay.begin(k2, whole())
        now = 800
        replay.begin(k3, whole()) // capacity 2: k1 goes
        assertNull(replay.lookup(k1))
        assertNotNull(replay.lookup(k2))
        now = 1_600 // k2 is 1100 ms old, past the 1000 ms ttl; k3 is 800 ms old
        assertNull(replay.lookup(k2))
        assertNotNull(replay.lookup(k3))
    }
}
