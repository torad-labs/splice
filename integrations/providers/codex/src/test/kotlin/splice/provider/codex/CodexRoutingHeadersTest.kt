// NEW (2026-09-05): the codex routing/session headers — a reconnect must read the prompt cache warm,
// and the set must be constant for a session so the WS connection key never churns on it.
package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope

class CodexRoutingHeadersTest {

    private val routing = CodexRoutingHeaders()

    private fun meta(session: String?, conversation: String? = "conv-1") = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.TEXT,
            effort = "high",
            summary = null,
            budgetTokens = null,
        ),
        route = TurnRoute(
            stream = true,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6",
            clientMaxTokens = null,
        ),
        scope = TurnScope(conversationKey = conversation, sessionId = session),
    )

    @Test
    fun `a session's turns carry the session id and a uuid thread id named twice`() {
        val headers = routing.forTurn(meta("sess-a"))
        assertEquals("sess-a", headers["session-id"])
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        assertTrue(headers.getValue("thread-id").matches(uuid), headers.toString())
        // Fix 4 of the Oct 27 Responses fixes: codex-rs names the thread a second time as x-client-request-id, on the
        // HTTP POST as on the websocket handshake (codex-api/src/endpoint/responses.rs:86-89).
        assertEquals(headers["thread-id"], headers["x-client-request-id"])
        // The routing hint is CodexSpeed's to write (gpt-speed), never this set's (CodexProviderTest pins it on the turn).
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
    fun `no session id means no session headers`() {
        assertEquals(emptyMap<String, String>(), routing.forTurn(meta(null)))
        assertEquals(emptyMap<String, String>(), routing.forTurn(meta("  ")))
    }
}
