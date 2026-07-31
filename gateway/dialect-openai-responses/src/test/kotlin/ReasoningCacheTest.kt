// RC-2 walls: any-id lookup, re-read grace (a client-retried request sees the same envelopes),
// TTL expiry against an injected clock, oldest-first eviction under both bounds, stale eviction,
// conversation scoping (review 2026-07-24: one instance serves every concurrent conversation on
// a head — a bare-id lookup could cross-inject), and the reasoningCacheActive gate seam.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `ttl expires IDLE entries`() {
        // The TTL is an IDLE timer (prefix stability, 2026-07-30): untouched entries still expire
        // exactly as before. The active-conversation case is pinned by the next two tests.
        var now = 0L
        val c = ReasoningCache(ttlMs = 100, clock = { now })
        c.put(CONV, listOf("call_a"), listOf("e1"))
        now = 99
        assertEquals(listOf("e1"), c.lookup(CONV, "call_a"), "not yet idle-expired")
        now = 200 // 101ms since the refresh at t=99 -> idle past the TTL
        assertNull(c.lookup(CONV, "call_a"))
    }

    @Test
    fun `an ACTIVE conversation never partially expires - the prefix-stability contract`() {
        // THE REGRESSION THIS PINS: the builder injects each round's reasoning immediately before
        // that round's first function_call, so losing ONE round mid-conversation deletes an item
        // from the middle of the input array and shifts everything after it. Turn N+1 then stops
        // being a prefix-extension of turn N and OpenAI re-bills the remainder. Before the fix,
        // round 1 expired on the raw insertion TTL while later rounds lived on -- measured at 7.7%
        // prefix reuse (PrefixStabilityDiagnostic).
        var now = 0L
        val c = ReasoningCache(ttlMs = 100, clock = { now })
        c.put(CONV, listOf("call_1"), listOf("e1"))
        // A long conversation: a new round every 60ms, each turn re-reading every earlier round
        // exactly as the builder's walk does.
        for (round in 2..10) {
            now += 60
            for (earlier in 1 until round) {
                assertEquals(
                    listOf("e$earlier"),
                    c.lookup(CONV, "call_$earlier"),
                    "round $earlier vanished at t=$now while the conversation was still active",
                )
            }
            c.put(CONV, listOf("call_$round"), listOf("e$round"))
        }
        // t=540, far beyond the 100ms TTL: every round is still resolvable, so the input array is
        // byte-identical to the previous turn's up to the append point.
        for (round in 1..10) assertEquals(listOf("e$round"), c.lookup(CONV, "call_$round"))
    }

    @Test
    fun `an idle conversation expires WHOLESALE, never half`() {
        // A clean one-time transition to no-injection beats continuous per-round churn: half a
        // conversation is exactly the prefix-shifting state the cache must never serve.
        var now = 0L
        val c = ReasoningCache(ttlMs = 100, clock = { now })
        c.put(CONV, listOf("call_1"), listOf("e1"))
        now = 60
        c.put(CONV, listOf("call_2"), listOf("e2"))
        now = 130 // call_1 is idle-expired; call_2 is not
        assertNull(c.lookup(CONV, "call_1"))
        assertNull(c.lookup(CONV, "call_2"), "the younger round must go with it, or the prefix shifts")
    }

    @Test
    fun `entry-count bound evicts the oldest CONVERSATION, sparing newer ones`() {
        // Was "evicts the oldest turn first" (review of #71): trimming one ROUND off a live
        // conversation leaves it half-cached, which shifts the input array mid-prefix. The unit of
        // eviction is now the conversation.
        val c = ReasoningCache(maxEntries = 2, clock = { 0L })
        c.put("conv-old", listOf("call_1"), listOf("e1"))
        c.put("conv-mid", listOf("call_2"), listOf("e2"))
        c.put("conv-new", listOf("call_3"), listOf("e3"))
        assertNull(c.lookup("conv-old", "call_1"), "oldest conversation evicted")
        assertEquals(listOf("e2"), c.lookup("conv-mid", "call_2"))
        assertEquals(listOf("e3"), c.lookup("conv-new", "call_3"))
    }

    @Test
    fun `byte ceiling evicts whole conversations until under bound`() {
        val c = ReasoningCache(maxTotalBytes = 10, clock = { 0L })
        c.put("conv-old", listOf("call_1"), listOf("aaaaa"))
        c.put("conv-mid", listOf("call_2"), listOf("bbbbb"))
        c.put("conv-new", listOf("call_3"), listOf("ccccc"))
        assertNull(c.lookup("conv-old", "call_1"))
        assertEquals(listOf("bbbbb"), c.lookup("conv-mid", "call_2"))
    }

    @Test
    fun `one oversized put evicts as many oldest conversations as the bound requires`() {
        // review 2026-07-24: the single-eviction case left the while-loop's 2nd+ iteration untested
        val c = ReasoningCache(maxTotalBytes = 12, clock = { 0L })
        c.put("conv-a", listOf("call_1"), listOf("aaaaa"))
        c.put("conv-b", listOf("call_2"), listOf("bbbbb"))
        c.put("conv-c", listOf("call_3"), listOf("cccccccccc"))
        assertNull(c.lookup("conv-a", "call_1"))
        assertNull(c.lookup("conv-b", "call_2"), "the second-oldest must go too - one eviction is not enough")
        assertEquals(listOf("cccccccccc"), c.lookup("conv-c", "call_3"))
    }

    @Test
    fun `a conversation that overflows the bound ALONE is dropped whole and stays non-injectable`() {
        // THE REVIEW OF #71: the earlier fallback trimmed the ACTIVE conversation one round at a
        // time, leaving newer rounds cached and older ones not — the exact mid-prefix shift this
        // cache exists to prevent. Wiping without a marker is not enough either: the conversation
        // would re-cache, overflow, and wipe again, so rounds keep LOSING reasoning they had.
        val c = ReasoningCache(maxEntries = 2, clock = { 0L })
        c.put(CONV, listOf("call_1"), listOf("e1"))
        c.put(CONV, listOf("call_2"), listOf("e2"))
        c.put(CONV, listOf("call_3"), listOf("e3")) // overflows on its own

        assertNull(c.lookup(CONV, "call_1"), "no partial state: every round goes")
        assertNull(c.lookup(CONV, "call_2"))
        assertNull(c.lookup(CONV, "call_3"))

        // ...and it must not creep back, or the overflow/wipe cycle restarts.
        c.put(CONV, listOf("call_4"), listOf("e4"))
        c.put(CONV, listOf("call_5"), listOf("e5"))
        assertNull(c.lookup(CONV, "call_4"), "a disabled conversation must not re-cache")
        assertNull(c.lookup(CONV, "call_5"))

        // Other conversations are unaffected — the marker is per-conversation.
        c.put("conv-other", listOf("call_9"), listOf("e9"))
        assertEquals(listOf("e9"), c.lookup("conv-other", "call_9"))
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
    fun `strips reasoning items, keeps everything else structurally identical, evicts the turn`() {
        // review 2026-07-24 (PR #46 thread): substring checks let a faulty transform mutate the
        // instruction, arguments, or flags unseen — the wall is the executable delta: the amended
        // request equals the original with ONLY the reasoning input items removed
        val cache = ReasoningCache(clock = { 0L })
        cache.put(CONV, listOf("call_a"), listOf("stale"))
        val amended = stripStaleReasoning(body, cache)!!
        val original = Json.parseToJsonElement(body).jsonObject
        val expectedInput = original.getValue("input").jsonArray.filterNot {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }
        val expected = JsonObject(original + ("input" to JsonArray(expectedInput)))
        assertEquals(expected, Json.parseToJsonElement(amended).jsonObject)
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
