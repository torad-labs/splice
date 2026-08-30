// NEW: the conversation-lifetime dedup registry (cross-turn recap staircase, 2026-08-26).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.dialect.responses.ConversationSummaryParts

class ConversationSummaryPartsTest {

    @Test
    fun `same conversation key returns the same instance across turns`() {
        val registry = ConversationSummaryParts()
        assertSame(registry.forConversation(null, "c1"), registry.forConversation(null, "c1"))
    }

    @Test
    fun `different conversations are isolated`() {
        val registry = ConversationSummaryParts()
        val a = registry.forConversation(null, "c1")!!
        val b = registry.forConversation(null, "c2")!!
        assertNotSame(a, b)
        a.markEmitted(0, "a part long enough to be recorded")
        assertEquals(-1, b.anchorOf("a part long enough to be recorded"))
    }

    @Test
    fun `null key returns null - caller falls back to the turn's own state`() {
        assertNull(ConversationSummaryParts().forConversation(null, null))
    }

    @Test
    fun `least recently fetched conversation is evicted at the bound`() {
        val registry = ConversationSummaryParts(maxConversations = 2)
        val a = registry.forConversation(null, "a")
        registry.forConversation(null, "b")
        registry.forConversation(null, "a") // touch: b is now eldest
        registry.forConversation(null, "c") // evicts b
        assertSame(a, registry.forConversation(null, "a"))
        val b2 = registry.forConversation(null, "b")!!
        assertEquals(-1, b2.anchorOf("anything at all, evicted state is gone"))
    }

    @Test
    fun `parts are trimmed to the bound at fetch time`() {
        val registry = ConversationSummaryParts(maxPartsPerConversation = 2)
        val parts = registry.forConversation(null, "c")!!
        parts.markEmitted(0, "part one - long enough to be recorded")
        parts.markEmitted(0, "part two - long enough to be recorded")
        parts.markEmitted(0, "part three - long enough to be recorded")
        registry.forConversation(null, "c")
        assertEquals(-1, parts.anchorOf("part one - long enough to be recorded"))
        assertEquals(0, parts.anchorOf("part two - long enough to be recorded"))
        assertEquals(1, parts.anchorOf("part three - long enough to be recorded"))
        // A trimmed part is re-admittable: its per-item record was dropped with it.
        assertEquals(true, parts.markEmitted(0, "part one - long enough to be recorded"))
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

    // The feature must not vanish for a client that sends no session id: that would silently drop
    // this dialect back to per-turn dedup, which is the exact staircase the registry exists to fix.
    @Test
    fun `no session id still scopes by conversation rather than refusing`() {
        val registry = ConversationSummaryParts()
        assertSame(
            registry.forConversation(null, "convo-1"),
            registry.forConversation("", "convo-1"),
        )
    }
}
