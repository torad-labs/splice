// NEW: foreground transport admits only known-head, well-formed opaque identities and phases.
package splice.launch.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.client.ForegroundToolPhase
import splice.launch.LaunchHead
import splice.launch.absentAuth
import splice.launch.launchHeadsOf
import splice.launch.runningHead

class ForegroundHookRouteTest {
    private val received = mutableListOf<Triple<String, String?, ForegroundToolPhase>>()
    private val route = ForegroundHookRoute(
        launchHeadsOf(LaunchHead(runningHead("synthetic"), absentAuth("fixture"), spec = null)),
        activity = { call -> received.add(Triple(call.sessionId, call.toolUseId, call.phase)) },
    )

    private fun body(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun handle(head: String, body: JsonObject?) {
        route.handle(head, body, "11111111-0000-4000-8000-000000000001")
    }

    @Test
    fun `start end and session end carry the exact opaque identifiers`() {
        handle("synthetic", body("""{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"start"}"""))
        handle("synthetic", body("""{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"end"}"""))
        handle(
            "synthetic",
            body("""{"session_id":"synthetic-session","tool_use_id":null,"phase":"session_end"}"""),
        )
        assertEquals(
            listOf(
                Triple("synthetic-session", "tool-1", ForegroundToolPhase.START),
                Triple("synthetic-session", "tool-1", ForegroundToolPhase.END),
                Triple("synthetic-session", null, ForegroundToolPhase.SESSION_END),
            ),
            received,
        )
    }

    @Test
    fun `unknown heads extra content wrong scalar types and unsupported phases change nothing`() {
        val valid = body("""{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"start"}""")
        handle("missing", valid)
        handle("synthetic", null)
        for (raw in listOf(
            """{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"start","tool_name":"private"}""",
            """{"session_id":123,"tool_use_id":"tool-1","phase":"start"}""",
            """{"session_id":"synthetic-session","tool_use_id":123,"phase":"start"}""",
            """{"session_id":"","tool_use_id":"tool-1","phase":"start"}""",
            """{"session_id":"synthetic-session","phase":"start"}""",
            """{"session_id":"synthetic-session","tool_use_id":"tool-1","phase":"heartbeat"}""",
        )) handle("synthetic", body(raw))
        assertTrue(received.isEmpty())
    }
}
