// RC-2 walls: any-id lookup, re-read grace (a client-retried request sees the same envelopes),
// TTL expiry against an injected clock, oldest-first eviction under both bounds, stale eviction.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.dialect.responses.ReasoningCache
import splice.dialect.responses.stripStaleReasoning

class ReasoningCacheTest {

    @Test
    fun `any id of the turn resolves the same ordered envelopes, and re-reads are free`() {
        val c = ReasoningCache(clock = { 1000L })
        c.put(listOf("call_a", "call_b"), listOf("e1", "e2"))
        assertEquals(listOf("e1", "e2"), c.lookup("call_a"))
        assertEquals(listOf("e1", "e2"), c.lookup("call_b"))
        assertEquals(listOf("e1", "e2"), c.lookup("call_a"), "retried requests re-read the same entry")
    }

    @Test
    fun `ttl expires entries`() {
        var now = 0L
        val c = ReasoningCache(ttlMs = 100, clock = { now })
        c.put(listOf("call_a"), listOf("e1"))
        now = 99
        assertEquals(listOf("e1"), c.lookup("call_a"))
        now = 101
        assertNull(c.lookup("call_a"))
    }

    @Test
    fun `entry-count bound evicts the oldest turn first`() {
        val c = ReasoningCache(maxEntries = 2, clock = { 0L })
        c.put(listOf("call_1"), listOf("e1"))
        c.put(listOf("call_2"), listOf("e2"))
        c.put(listOf("call_3"), listOf("e3"))
        assertNull(c.lookup("call_1"), "oldest evicted")
        assertEquals(listOf("e2"), c.lookup("call_2"))
        assertEquals(listOf("e3"), c.lookup("call_3"))
    }

    @Test
    fun `byte ceiling evicts oldest until under bound`() {
        val c = ReasoningCache(maxTotalBytes = 10, clock = { 0L })
        c.put(listOf("call_1"), listOf("aaaaa"))
        c.put(listOf("call_2"), listOf("bbbbb"))
        c.put(listOf("call_3"), listOf("ccccc"))
        assertNull(c.lookup("call_1"))
        assertEquals(listOf("bbbbb"), c.lookup("call_2"))
    }

    @Test
    fun `stale eviction drops the whole turn by any of its ids`() {
        val c = ReasoningCache(clock = { 0L })
        c.put(listOf("call_a", "call_b"), listOf("e1"))
        c.evictByToolId("call_b")
        assertNull(c.lookup("call_a"))
    }

    @Test
    fun `empty puts are ignored`() {
        val c = ReasoningCache(clock = { 0L })
        c.put(emptyList(), listOf("e1"))
        c.put(listOf("call_a"), emptyList())
        assertNull(c.lookup("call_a"))
    }
}

// RC-4 walls: the invalid_encrypted_content recovery strips ONLY reasoning items, keeps order,
// evicts the affected turns, and declines bodies that carry no reasoning (not our amendment).
class StripStaleReasoningTest {

    private val body =
        """{"model":"m","input":[""" +
            """{"role":"user","content":"go"},""" +
            """{"type":"reasoning","id":"rs_1","encrypted_content":"stale"},""" +
            """{"type":"function_call","call_id":"call_a","name":"run","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"call_a","output":"ok"}],""" +
            """"store":false,"stream":true}"""

    @Test
    fun `strips reasoning items, keeps everything else in order, evicts the turn`() {
        val cache = ReasoningCache(clock = { 0L })
        cache.put(listOf("call_a"), listOf("stale"))
        val amended = stripStaleReasoning(body, cache)!!
        org.junit.jupiter.api.Assertions.assertFalse(amended.contains("\"reasoning\""))
        org.junit.jupiter.api.Assertions.assertTrue(amended.contains("call_a"))
        org.junit.jupiter.api.Assertions.assertTrue(
            amended.indexOf("function_call") < amended.indexOf("function_call_output"),
        )
        assertNull(cache.lookup("call_a"), "stale turn evicted")
    }

    @Test
    fun `a body with no reasoning items is not ours to amend`() {
        val plain =
            """{"model":"m","input":[{"role":"user","content":"go"}],"store":false,"stream":true}"""
        assertNull(stripStaleReasoning(plain, ReasoningCache(clock = { 0L })))
    }
}
