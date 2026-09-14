// NEW: v0.4.0 FEATURES.md §8 — a client that falls a full buffer behind on its notification
// stream loses the stale backlog, never the fact that its cached lists may have changed.
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
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
        repeat(BUFFER + 1) { sessions.fanOut(SERVER, """{"n":$it}""") }
        val drained = generateSequence { session.stream.tryReceive().getOrNull() }.toList()
        val invalidations = listOf("tools", "prompts", "resources").map {
            """{"jsonrpc":"2.0","method":"notifications/$it/list_changed"}"""
        }
        assertEquals(invalidations + """{"n":$BUFFER}""", drained)
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
