// NEW: Oct 10, 2026 — step 3: a session held back by how its newest request ended says so on its row, and only its own.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionEndingRowTest {
    @TempDir lateinit var root: Path

    private fun rows(held: Map<String, SessionEnding>): Map<String, JsonObject> {
        val routes = SessionsRoutes(
            TeamRig(root).registry,
            TestTranscripts(),
            facts = SessionRowFacts(endingOf = SessionEndingOf { id -> held[id] }),
        )
        return Json.parseToJsonElement(routes.sessionsJson()).jsonObject
            .getValue("sessions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("session_id").jsonPrimitive.content }
    }

    @Test
    fun `a session at its plan's limit names the outcome, the account and when the window comes back`() {
        val limit = SessionEnding("error:plan-limit", "Torad", resetMs = 1_791_700_000_000, atMs = 1_791_690_000_000)

        val ended = rows(mapOf(LEAD to limit)).getValue(LEAD).getValue("ended_by").jsonObject

        assertEquals("error:plan-limit", ended.getValue("outcome").jsonPrimitive.content)
        assertEquals("Torad", ended.getValue("account").jsonPrimitive.content)
        assertEquals(1_791_700_000_000, ended.getValue("reset_ms").jsonPrimitive.long)
    }

    @Test
    fun `a signed-out session has no reset to name, and a session nothing holds carries no ending`() {
        val signedOut = SessionEnding("error:auth-missing", null, resetMs = null, atMs = 1_791_690_000_000)

        val listed = rows(mapOf(LEAD to signedOut))

        assertFalse(listed.getValue(LEAD).getValue("ended_by").jsonObject.containsKey("reset_ms"))
        assertFalse(listed.getValue(BUILDER).containsKey("ended_by"))
    }
}
