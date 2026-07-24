// RC-2 walls: any-id lookup, re-read grace (a client-retried request sees the same envelopes),
// TTL expiry against an injected clock, oldest-first eviction under both bounds, stale eviction,
// conversation scoping (review 2026-07-24: one instance serves every concurrent conversation on
// a head — a bare-id lookup could cross-inject), and the reasoningCacheActive gate seam.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.responses.ReasoningCache
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoningCacheActive
import splice.dialect.responses.stripStaleReasoning

private const val CONV = "conv-1"

class ReasoningCacheTest {

    @Test
    fun `any id of the turn resolves the same ordered envelopes, and re-reads are free`() {
        val c = ReasoningCache(clock = { 1000L })
        c.put(CONV, listOf("call_a", "call_b"), listOf("e1", "e2"))
        assertEquals(listOf("e1", "e2"), c.lookup(CONV, "call_a"))
        assertEquals(listOf("e1", "e2"), c.lookup(CONV, "call_b"))
        assertEquals(listOf("e1", "e2"), c.lookup(CONV, "call_a"), "retried requests re-read the same entry")
    }

    @Test
    fun `lookups are conversation-scoped - another conversation's identical id never resolves`() {
        val c = ReasoningCache(clock = { 0L })
        c.put("conv-a", listOf("call_1"), listOf("plan-of-a"))
        assertNull(c.lookup("conv-b", "call_1"), "eli risk 8: cross-conversation bleed")
        assertNull(c.lookup(null, "call_1"), "the null scope is its own namespace")
        assertEquals(listOf("plan-of-a"), c.lookup("conv-a", "call_1"))
    }

    @Test
    fun `eviction is deliberately bare-id - a stale 400 clears every scope that carried the id`() {
        val c = ReasoningCache(clock = { 0L })
        c.put("conv-a", listOf("call_1"), listOf("e1"))
        c.put("conv-b", listOf("call_1"), listOf("e2"))
        c.evictByToolId("call_1")
        assertNull(c.lookup("conv-a", "call_1"), "over-evicting on collision costs a miss, never a wrong injection")
        assertNull(c.lookup("conv-b", "call_1"))
    }

    @Test
    fun `ttl expires entries`() {
        var now = 0L
        val c = ReasoningCache(ttlMs = 100, clock = { now })
        c.put(CONV, listOf("call_a"), listOf("e1"))
        now = 99
        assertEquals(listOf("e1"), c.lookup(CONV, "call_a"))
        now = 101
        assertNull(c.lookup(CONV, "call_a"))
    }

    @Test
    fun `entry-count bound evicts the oldest turn first`() {
        val c = ReasoningCache(maxEntries = 2, clock = { 0L })
        c.put(CONV, listOf("call_1"), listOf("e1"))
        c.put(CONV, listOf("call_2"), listOf("e2"))
        c.put(CONV, listOf("call_3"), listOf("e3"))
        assertNull(c.lookup(CONV, "call_1"), "oldest evicted")
        assertEquals(listOf("e2"), c.lookup(CONV, "call_2"))
        assertEquals(listOf("e3"), c.lookup(CONV, "call_3"))
    }

    @Test
    fun `byte ceiling evicts oldest until under bound`() {
        val c = ReasoningCache(maxTotalBytes = 10, clock = { 0L })
        c.put(CONV, listOf("call_1"), listOf("aaaaa"))
        c.put(CONV, listOf("call_2"), listOf("bbbbb"))
        c.put(CONV, listOf("call_3"), listOf("ccccc"))
        assertNull(c.lookup(CONV, "call_1"))
        assertEquals(listOf("bbbbb"), c.lookup(CONV, "call_2"))
    }

    @Test
    fun `one oversized put evicts as many oldest entries as the bound requires`() {
        // review 2026-07-24: the single-eviction case left the while-loop's 2nd+ iteration untested
        val c = ReasoningCache(maxTotalBytes = 12, clock = { 0L })
        c.put(CONV, listOf("call_1"), listOf("aaaaa"))
        c.put(CONV, listOf("call_2"), listOf("bbbbb"))
        c.put(CONV, listOf("call_3"), listOf("cccccccccc"))
        assertNull(c.lookup(CONV, "call_1"))
        assertNull(c.lookup(CONV, "call_2"), "the second-oldest must go too - one eviction is not enough")
        assertEquals(listOf("cccccccccc"), c.lookup(CONV, "call_3"))
    }

    @Test
    fun `stale eviction drops the whole turn by any of its ids`() {
        val c = ReasoningCache(clock = { 0L })
        c.put(CONV, listOf("call_a", "call_b"), listOf("e1"))
        c.evictByToolId("call_b")
        assertNull(c.lookup(CONV, "call_a"))
    }

    @Test
    fun `empty toolIds are ignored and never occupy an eviction slot`() {
        // review 2026-07-24: an orphan entry has no key, so a lookup can't see it — the observable
        // effect of a dropped guard is the slot it steals from a real turn under bound pressure
        val c = ReasoningCache(maxEntries = 1, clock = { 0L })
        c.put(CONV, emptyList(), listOf("e1"))
        c.put(CONV, listOf("call_a"), listOf("e2"))
        assertEquals(listOf("e2"), c.lookup(CONV, "call_a"), "an orphan entry must not evict the real turn")
    }

    @Test
    fun `empty envelopes are ignored`() {
        val c = ReasoningCache(clock = { 0L })
        c.put(CONV, listOf("call_a"), emptyList())
        assertNull(c.lookup(CONV, "call_a"))
    }
}

// The ONE gate every cache touch point routes through (capture, collect, lookup, include) — a
// regression dropping the !compact conjunct must fail here, not silently ship (review 2026-07-24).
class ReasoningCacheActiveTest {

    private val quirksOn = ResponsesQuirks(providerTag = "t", reasoningCache = true)

    @Test
    fun `active only when the quirk is on AND the turn is not a compaction`() {
        assertTrue(reasoningCacheActive(quirksOn, compact = false))
        assertFalse(reasoningCacheActive(quirksOn, compact = true), "compaction turns never touch the cache")
        assertFalse(reasoningCacheActive(quirksOn.copy(reasoningCache = false), compact = false))
        assertFalse(reasoningCacheActive(quirksOn.copy(reasoningCache = false), compact = true))
    }
}

// RC-4 walls: the invalid_encrypted_content recovery strips ONLY reasoning items, keeps order,
// evicts ONLY the rounds those items belonged to (review 2026-07-24: whole-history eviction ended
// cache continuity for the conversation's life), and declines bodies that carry no reasoning.
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
        cache.put(CONV, listOf("call_a"), listOf("stale"))
        val amended = stripStaleReasoning(body, cache)!!
        assertFalse(amended.contains("\"reasoning\""))
        assertTrue(amended.contains("call_a"))
        assertTrue(amended.indexOf("function_call") < amended.indexOf("function_call_output"))
        assertNull(cache.lookup(CONV, "call_a"), "stale turn evicted")
    }

    @Test
    fun `eviction is scoped to the dropped rounds - other rounds keep their entries`() {
        val twoRounds =
            """{"model":"m","input":[""" +
                """{"role":"user","content":"go"},""" +
                """{"type":"reasoning","id":"rs_1","encrypted_content":"stale1"},""" +
                """{"type":"function_call","call_id":"call_a","name":"run","arguments":"{}"},""" +
                """{"type":"function_call_output","call_id":"call_a","output":"ok"},""" +
                """{"type":"function_call","call_id":"call_orphan","name":"run","arguments":"{}"},""" +
                """{"type":"function_call_output","call_id":"call_orphan","output":"ok"}],""" +
                """"store":false,"stream":true}"""
        val cache = ReasoningCache(clock = { 0L })
        cache.put(CONV, listOf("call_a"), listOf("stale1"))
        cache.put(CONV, listOf("call_orphan"), listOf("healthy"))
        val amended = stripStaleReasoning(twoRounds, cache)!!
        assertFalse(amended.contains("\"reasoning\""))
        assertNull(cache.lookup(CONV, "call_a"), "the round under the dropped reasoning is evicted")
        assertEquals(
            listOf("healthy"),
            cache.lookup(CONV, "call_orphan"),
            "a round with no reasoning item in the body had nothing stale to evict",
        )
    }

    @Test
    fun `a body with no reasoning items is not ours to amend`() {
        val plain =
            """{"model":"m","input":[{"role":"user","content":"go"}],"store":false,"stream":true}"""
        assertNull(stripStaleReasoning(plain, ReasoningCache(clock = { 0L })))
    }
}
