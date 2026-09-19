// NEW: v0.4.0 FEATURES.md §8 — a client that falls a full buffer behind on its notification
// stream loses the stale backlog, never the fact that its cached lists may have changed.
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.mcp.HostClock
import splice.control.mcp.McpSessions

private const val BUFFER = 256
private const val SERVER = "fs"

class McpSessionsTest {
    private val sessions = McpSessions(HostClock { 0L })

    @Test
    fun `a full stream collapses the backlog to the list invalidations plus the newest notification`() {
        val session = sessions.create(SERVER, buildJsonObject {})
        val newest = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
        repeat(BUFFER + 1) { sessions.fanOut(SERVER, newest) }
        val drained = generateSequence { session.stream.tryReceive().getOrNull() }.toList()
        val invalidations = listOf("tools", "prompts", "resources").map {
            """{"jsonrpc":"2.0","method":"notifications/$it/list_changed"}"""
        }
        assertEquals(invalidations + newest, drained)
    }

    // V4-148. Mutant: register the adopted session with put instead of putIfAbsent. A client that
    // retried its request while the first adoption was still handshaking gets two sessions under one
    // id, and the one the map forgets holds its child's notifications where nobody reads them.
    @Test
    fun `adopting one id twice keeps one session, and the loser's stream is closed`() {
        val first = sessions.create(SERVER, buildJsonObject {}, "held-by-the-client")
        val second = sessions.create(SERVER, buildJsonObject {}, "held-by-the-client")

        assertSame(first, second)
        assertSame(first, sessions.get(SERVER, "held-by-the-client"))
        assertTrue(first.adopted, "minted under the client's own id")
    }

    @Test
    fun `resource update overflow ends the session with an explicit error instead of false invalidations`() {
        val session = sessions.create(SERVER, buildJsonObject {})
        val update = """{"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"test:item"}}"""
        repeat(BUFFER + 1) { sessions.fanOut(SERVER, update) }
        val drained = generateSequence { session.stream.tryReceive().getOrNull() }.toList()
        assertEquals(1, drained.size)
        assertTrue(drained.single().contains("overflow"), drained.toString())
        assertTrue(session.stream.tryReceive().isClosed)
        assertEquals(null, sessions.get(SERVER, session.id), "next request must reinitialize")
    }

    @Test
    fun `eviction activity includes the time the last session ended`() {
        var now = 100L
        val timed = McpSessions(HostClock { now })
        val session = timed.create(SERVER, buildJsonObject {})
        now = 200L
        timed.end(SERVER, session.id)
        assertEquals(200L, timed.lastActivity(SERVER))
    }

    @Test
    fun `a server is busy for one idle window after its last session ends, then idle`() {
        val session = sessions.create(SERVER, buildJsonObject {})
        sessions.end(SERVER, session.id)
        assertTrue(sessions.busy(SERVER, now = 5_000L, idleMillis = 10_000L))
        assertTrue(!sessions.busy(SERVER, now = 10_000L, idleMillis = 10_000L))
        assertTrue(!sessions.busy("other", now = 0L, idleMillis = 10_000L), "another server never ended a session")
    }

    @Test
    fun `below the buffer nothing is dropped or reordered`() {
        val session = sessions.create(SERVER, buildJsonObject {})
        repeat(BUFFER) { sessions.fanOut(SERVER, "$it") }
        val drained = generateSequence { session.stream.tryReceive().getOrNull() }.toList()
        assertEquals((0 until BUFFER).map { "$it" }, drained)
        assertTrue(session.stream.tryReceive().isFailure)
    }
}
