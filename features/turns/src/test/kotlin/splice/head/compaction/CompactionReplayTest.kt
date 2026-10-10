// NEW (2026-09-05): the store of detached compactions' answers — what a byte-identical retry finds.
package splice.head.compaction

import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.head.wire.FrameRecording
import java.lang.management.ManagementFactory

class CompactionReplayTest {

    private var now = 0L
    private val replay = CompactionReplay(clock = ElapsedClock { now }, ttlMs = 1_000, capacity = 2)

    private fun whole() = FrameRecording().apply {
        append("event: message_start\n\n")
        append("event: message_stop\n\n")
        complete(whole = true)
    }

    @Test
    fun `the key is the session and the upstream bytes - no session, no key`() {
        assertNull(replay.key(null, "{}"))
        assertNull(replay.key(" ", "{}"))
        assertEquals(replay.key("s", """{"a":1}"""), replay.key("s", """{"a":1}"""))
        assertNotEquals(replay.key("s", """{"a":1}"""), replay.key("s", """{"a":2}"""))
        assertNotEquals(replay.key("s", "{}"), replay.key("t", "{}"))
        // A recorded pre-tail hash outranks the upstream bytes: a tail that resolved differently on
        // the retry (project found late, file edited) still finds the first attempt (review 2026-09-14).
        val pre = replay.bodyHash("""{"client":1}""")
        assertEquals(replay.key("s", "tail A", pre), replay.key("s", "tail B", pre))
        assertNotEquals(replay.key("s", "tail A", pre), replay.key("s", "tail A"))
    }

    @Test
    fun `a cached identity does not serialize a large request again`() {
        val request = buildJsonObject { put("payload", JsonPrimitive("x".repeat(1_400_000))) }
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: error("JVM allocation counter is required")
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(5) { replay.key("synthetic", request, "cached-synthetic") }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val key = replay.key("synthetic", request, "cached-synthetic")
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals("synthetic:cached-synthetic", key)
        assertTrue(allocated < 64 * 1024, "cached_request_identity_allocated_bytes=$allocated")
    }

    @Test
    fun `a borrowed tree identity matches the exact legacy bytes`() {
        val bytes = """{"quote\\\"":[1,2,1E2,true,false,{},[]],"text":"café 🧪"}"""
        val tree = Json.parseToJsonElement(bytes).jsonObject
        assertEquals(replay.bodyHash(bytes), replay.bodyHash(tree))
        assertEquals(replay.key("synthetic", bytes), replay.key("synthetic", tree))
        assertNull(replay.key(null, tree))
        assertNull(replay.key(" ", tree))
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
        replay.consumed(key, kept)
        assertNull(replay.lookup(key), "a delivered replay is spent")
    }

    @Test
    fun `a late consumption of a delivered replay leaves a newer compaction under the same key alone`() {
        val key = checkNotNull(replay.key("s", "{}"))
        val delivered = whole()
        replay.begin(key, delivered)
        replay.finish(key, delivered, keep = true)
        assertSame(delivered, replay.lookup(key))
        val newer = FrameRecording().apply { append("event: message_start\n\n") }
        replay.begin(key, newer)
        replay.consumed(key, delivered)
        assertSame(newer, replay.lookup(key), "the retry of the newer compaction still finds its recording")
    }

    private class MapRecordings : CompactionRecordings {
        val files = mutableMapOf<String, KeptAnswer>()

        override fun save(key: String, generation: String, frames: List<String>) {
            files[key] = KeptAnswer(generation, frames)
        }

        override fun load(key: String): KeptAnswer? = files[key]

        override fun remove(key: String, generation: String) {
            if (files[key]?.generation == generation) files.remove(key)
        }
    }

    @Test
    fun `a late consumption of an older delivery leaves the newer answer's file after eviction`() {
        val store = MapRecordings()
        val withStore = CompactionReplay(store, clock = ElapsedClock { now }, ttlMs = 1_000, capacity = 2)
        val key = checkNotNull(withStore.key("s", "{}"))
        val older = whole()
        withStore.begin(key, older)
        withStore.finish(key, older, keep = true)
        val newer = FrameRecording().apply {
            append("event: message_start\n\n")
            append("event: newer\n\n")
            complete(whole = true)
        }
        withStore.begin(key, newer)
        withStore.finish(key, newer, keep = true)
        // Two compactions still driving: capacity evicts the settled newer entry from memory, its file stays.
        withStore.begin("other-1", FrameRecording().apply { append("event: running\n\n") })
        withStore.begin("other-2", FrameRecording().apply { append("event: running\n\n") })
        assertNull(withStore.lookup("absent"), "the lookup sweeps")

        withStore.consumed(key, older)

        assertEquals(newer.frames(), store.load(key)?.frames, "the older delivery spent a file that is not its own")
        val found = checkNotNull(withStore.lookup(key)) { "the retry of the newer compaction finds its answer" }
        assertEquals(newer.generation, found.generation, "that answer is the newer one, read back from its file")
        withStore.consumed(key, newer)
        assertNull(store.load(key), "the owner's delivery spends its file")
        assertNull(withStore.lookup(key), "and the retry after it runs upstream")
    }

    @Test
    fun `an owner evicted from both caches while it is delivering still spends its file`() {
        val store = MapRecordings()
        val withStore = CompactionReplay(store, clock = ElapsedClock { now }, ttlMs = 1_000, capacity = 2)
        val key = checkNotNull(withStore.key("s", "{}"))
        val owner = whole()
        withStore.begin(key, owner)
        withStore.finish(key, owner, keep = true)
        // Delivering the owner, other kept answers fill the memory entries and anything bounded that remembers owners.
        repeat(8) { index ->
            val other = whole()
            withStore.begin("other-$index", other)
            withStore.finish("other-$index", other, keep = true)
        }
        assertNull(withStore.lookup("absent"), "the lookup sweeps")

        withStore.consumed(key, owner)

        assertNull(store.load(key), "the delivered answer is spent on disk")
        assertNull(withStore.lookup(key), "and a second identical request runs upstream")
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

    /** Review of PR 137: insertion order alone evicted the oldest-BEGUN entry, which can be a
     *  compaction still driving whose retry has not arrived yet. A settled one goes first. */
    @Test
    fun `past capacity a settled recording goes before one still in flight`() {
        val live = checkNotNull(replay.key("s", "live"))
        val settled = checkNotNull(replay.key("s", "settled"))
        val third = checkNotNull(replay.key("s", "third"))
        val inFlight = FrameRecording().apply { append("event: message_start\n\n") }
        replay.begin(live, inFlight) // oldest, and still driving
        replay.begin(settled, whole())
        replay.begin(third, FrameRecording()) // capacity 2: the settled one goes, not the oldest
        assertSame(inFlight, replay.lookup(live), "a compaction still in flight keeps its entry")
        assertNull(replay.lookup(settled))
        assertNotNull(replay.lookup(third))
        replay.begin(checkNotNull(replay.key("s", "fourth")), FrameRecording()) // all in flight: oldest goes
        assertNull(replay.lookup(live))
        assertNotNull(replay.lookup(third))
    }
}
