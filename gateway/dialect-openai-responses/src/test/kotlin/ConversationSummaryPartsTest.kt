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
        assertSame(registry.forConversation("c1"), registry.forConversation("c1"))
    }

    @Test
    fun `different conversations are isolated`() {
        val registry = ConversationSummaryParts()
        val a = registry.forConversation("c1")!!
        val b = registry.forConversation("c2")!!
        assertNotSame(a, b)
        a.markEmitted(0, "a part long enough to be recorded")
        assertEquals(-1, b.anchorOf("a part long enough to be recorded"))
    }

    @Test
    fun `null key returns null - caller falls back to the turn's own state`() {
        assertNull(ConversationSummaryParts().forConversation(null))
    }

    @Test
    fun `least recently fetched conversation is evicted at the bound`() {
        val registry = ConversationSummaryParts(maxConversations = 2)
        val a = registry.forConversation("a")
        registry.forConversation("b")
        registry.forConversation("a") // touch: b is now eldest
        registry.forConversation("c") // evicts b
        assertSame(a, registry.forConversation("a"))
        val b2 = registry.forConversation("b")!!
        assertEquals(-1, b2.anchorOf("anything at all, evicted state is gone"))
    }

    @Test
    fun `parts are trimmed to the bound at fetch time`() {
        val registry = ConversationSummaryParts(maxPartsPerConversation = 2)
        val parts = registry.forConversation("c")!!
        parts.markEmitted(0, "part one - long enough to be recorded")
        parts.markEmitted(0, "part two - long enough to be recorded")
        parts.markEmitted(0, "part three - long enough to be recorded")
        registry.forConversation("c")
        assertEquals(-1, parts.anchorOf("part one - long enough to be recorded"))
        assertEquals(0, parts.anchorOf("part two - long enough to be recorded"))
        assertEquals(1, parts.anchorOf("part three - long enough to be recorded"))
        // A trimmed part is re-admittable: its per-item record was dropped with it.
        assertEquals(true, parts.markEmitted(0, "part one - long enough to be recorded"))
    }
}
