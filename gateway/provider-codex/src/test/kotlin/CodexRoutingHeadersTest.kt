// NEW (2026-09-05): the codex routing/session headers — a reconnect must read the prompt cache warm,
// and the set must be constant for a session so the WS connection key never churns on it.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.provider.codex.CodexRoutingHeaders

class CodexRoutingHeadersTest {

    private val routing = CodexRoutingHeaders()

    private fun meta(session: String?, conversation: String? = "conv-1") = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-codex--gpt-5.6-sol",
        upstreamModel = "gpt-5.6",
        clientMaxTokens = null,
        effort = "high",
        summary = null,
        budgetTokens = null,
        conversationKey = conversation,
        sessionId = session,
    )

    @Test
    fun `a session's turns carry the session id, a uuid thread id and the model routing hint`() {
        val headers = routing.forTurn(meta("sess-a"))
        assertEquals("sess-a", headers["session-id"])
        assertEquals("model=gpt-5.6", headers["x-codex-routing-hint"])
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        assertTrue(headers.getValue("thread-id").matches(uuid), headers.toString())
        assertEquals(3, headers.size, headers.toString())
    }

    @Test
    fun `the set is constant across a session's conversations - a compaction keeps its affinity`() {
        assertEquals(routing.forTurn(meta("sess-a", "conv-1")), routing.forTurn(meta("sess-a", "conv-2")))
    }

    @Test
    fun `two sessions never share a thread id`() {
        assertNotEquals(routing.forTurn(meta("sess-a"))["thread-id"], routing.forTurn(meta("sess-b"))["thread-id"])
    }

    @Test
    fun `no session id means the routing hint alone`() {
        val hintOnly = mapOf("x-codex-routing-hint" to "model=gpt-5.6")
        assertEquals(hintOnly, routing.forTurn(meta(null)))
        assertEquals(hintOnly, routing.forTurn(meta("  ")))
    }
}
