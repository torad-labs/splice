// NEW: the conversation-lifetime dedup registry (cross-turn recap staircase, 2026-08-26).
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.SharedSummaryParts
import splice.core.util.ElapsedClock
import splice.dialect.responses.ConversationSummaryParts
import splice.dialect.responses.ResponsesConversationIdentity
import splice.dialect.responses.SummaryDedup
import splice.dialect.responses.SummaryRoundScope

class ConversationSummaryPartsTest {

    @Test
    fun `same conversation key returns the same instance across turns`() {
        val registry = ConversationSummaryParts()
        assertSame(registry.forConversation("s", "c1"), registry.forConversation("s", "c1"))
    }

    @Test
    fun `different conversations are isolated`() {
        val registry = ConversationSummaryParts()
        val a = registry.forConversation("s", "c1")!!
        val b = registry.forConversation("s", "c2")!!
        assertNotSame(a, b)
        a.markEmitted(0, "a part long enough to be recorded")
        assertEquals(-1, b.anchorOf("a part long enough to be recorded"))
    }

    @Test
    fun `missing either identity returns null - caller falls back to turn state`() {
        val registry = ConversationSummaryParts()
        assertNull(registry.forConversation(null, "c"))
        assertNull(registry.forConversation("s", null))
    }

    @Test
    fun `least recently fetched conversation is evicted at the bound`() {
        val registry = ConversationSummaryParts(maxConversations = 2)
        val a = registry.forConversation("s", "a")
        registry.forConversation("s", "b")
        registry.forConversation("s", "a") // touch: b is now eldest
        registry.forConversation("s", "c") // evicts b
        assertSame(a, registry.forConversation("s", "a"))
        val b2 = registry.forConversation("s", "b")!!
        assertEquals(-1, b2.anchorOf("anything at all, evicted state is gone"))
    }

    @Test
    fun `live parts stop admission at the count bound`() = runTest {
        val registry = ConversationSummaryParts(maxPartsPerConversation = 2)
        val owner = registry.ownerForConversation("s", "c")!!
        owner.withRound { parts ->
            assertTrue(parts.markEmitted(0, "part one - long enough to be recorded"))
            assertTrue(parts.markEmitted(0, "part two - long enough to be recorded"))
            assertTrue(parts.markEmitted(0, "part three - displayed but not recorded"))
        }
        val parts = registry.forConversation("s", "c")!!
        assertEquals(0, parts.anchorOf("part one - long enough to be recorded"))
        assertEquals(1, parts.anchorOf("part two - long enough to be recorded"))
        assertEquals(-1, parts.anchorOf("part three - displayed but not recorded"))
    }

    @Test
    fun `live parts stop admission at the byte bound`() = runTest {
        val registry = ConversationSummaryParts(maxPartsPerConversation = 10, maxBytesPerConversation = 65)
        val owner = registry.ownerForConversation("s", "c")!!
        val p1 = "1".repeat(30)
        val p2 = "2".repeat(30)
        val p3 = "3".repeat(30)
        owner.withRound { parts ->
            assertTrue(parts.markEmitted(0, p1))
            assertTrue(parts.markEmitted(0, p2))
            assertTrue(parts.markEmitted(0, p3))
        }
        val parts = registry.forConversation("s", "c")!!
        assertEquals(0, parts.anchorOf(p1))
        assertEquals(1, parts.anchorOf(p2))
        assertEquals(-1, parts.anchorOf(p3))
    }

    @Test
    fun `trimming one equal occurrence preserves the surviving item's exact record`() = runTest {
        val scope = SummaryRoundScope(SharedSummaryParts())
        val shared = "the same paragraph in two distinct reasoning items"
        scope.withRound { parts ->
            assertTrue(parts.markEmitted(0, shared))
            assertTrue(parts.markEmitted(1, shared))
            assertTrue(parts.markEmitted(1, "the newest distinct tail paragraph"))
        }
        scope.parts.trimToLast(2)
        scope.withRound { parts ->
            assertTrue(parts.markEmitted(0, shared), "trimmed item 0 occurrence is re-admittable")
            assertFalse(parts.markEmitted(1, shared), "surviving item 1 occurrence remains deduped")
        }
    }

    @Test
    fun `candidate tracking stops at the live count bound and later items display`() {
        val shared = SharedSummaryParts(maxParts = 2)
        val repeated = "one retained paragraph shared by many active items"
        shared.beginRound()
        shared.markEmitted(0, repeated)
        shared.endRound()
        shared.beginRound()
        val dedup = SummaryDedup(active = true, shared)

        assertTrue(dedup.suppress(0, repeated))
        assertTrue(dedup.suppress(1, repeated))
        assertFalse(dedup.suppress(2, repeated), "the over-bound item must display without allocating candidates")
        shared.endRound()
    }

    @Test
    fun `idle TTL expires a conversation wholesale`() {
        var now = 0L
        val registry = ConversationSummaryParts(ttlMs = 10, clock = ElapsedClock { now })
        val original = registry.forConversation("s", "c")
        now = 11
        assertNotSame(original, registry.forConversation("s", "c"))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `one scope serializes the complete round rather than individual events`() = runTest {
        val scope = SummaryRoundScope(SharedSummaryParts())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false
        val first = async {
            scope.withRound { parts ->
                parts.markEmitted(0, "first round begins with this paragraph")
                firstEntered.complete(Unit)
                releaseFirst.await()
                parts.markEmitted(0, "first round ends with this paragraph")
            }
        }
        firstEntered.await()
        val second = async {
            scope.withRound { parts ->
                secondEntered = true
                parts.markEmitted(0, "second round paragraph")
            }
        }
        runCurrent()
        assertFalse(secondEntered, "the second round entered before the first completed")
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `TTL cannot replace a scope while its round is active`() = runTest {
        var now = 0L
        val registry = ConversationSummaryParts(ttlMs = 10, clock = ElapsedClock { now })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var overlap = false
        val first = async {
            registry.ownerForConversation("s", "c")!!.withRound {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        now = 11
        val second = async {
            registry.ownerForConversation("s", "c")!!.withRound { overlap = true }
        }
        runCurrent()
        assertFalse(overlap, "TTL created a second scope beside the active one")
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(overlap)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `LRU pressure cannot replace a scope while its round is active`() = runTest {
        val registry = ConversationSummaryParts(maxConversations = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var overlap = false
        val first = async {
            registry.ownerForConversation("s", "active")!!.withRound {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        registry.ownerForConversation("s", "neighbor")!!.withRound { }
        val second = async {
            registry.ownerForConversation("s", "active")!!.withRound { overlap = true }
        }
        runCurrent()
        assertFalse(overlap, "LRU pressure created a second scope beside the active one")
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(overlap)
    }

    @Test
    fun `shared composite identity is injective across ambiguous concatenations`() {
        assertNotEquals(
            ResponsesConversationIdentity.chainKey("a", "bc"),
            ResponsesConversationIdentity.chainKey("ab", "c"),
        )
    }

    @Test
    fun `shared composite identity is injective when values contain the legacy delimiter`() {
        val encoded = ResponsesConversationIdentity.chainKey("a", "\u0000b")
        assertEquals("1:a2:\u0000b", encoded)
        assertNotEquals(encoded, ResponsesConversationIdentity.chainKey("a\u0000", "b"))
    }

    // conversationKey is a hash of the first user message's TEXT, so two sessions that open with
    // identical words collide on it BY DESIGN (TurnMeta.sessionId: "consumers must mix BOTH, never
    // either alone"). Sharing one dedup instance across them SUPPRESSES a reasoning part rather
    // than merely missing a cache. Review 2026-08-28 (PR 99, comment 1).
    @Test
    fun `two sessions opening with identical words do not share dedup state`() {
        val registry = ConversationSummaryParts()
        val a = registry.forConversation("session-a", "same-opening-words")!!
        val b = registry.forConversation("session-b", "same-opening-words")!!
        assertNotSame(a, b)
    }
}
