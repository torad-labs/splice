// NEW: Oct 11, 2026 — with reading off, a session still names the model splice served it, so Continue on leaves that
// model out of its menu and keeps it as the move's "from" (Marlin, rewalk 4).
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionServedRowTest {
    @TempDir lateinit var root: Path

    private val rig by lazy { TeamRig(root) }
    private val routes by lazy {
        SessionsRoutes(
            rig.registry,
            TestTranscripts(),
            settings = TranscriptViewSettings { false },
            facts = SessionRowFacts(servedOf = SessionServedOf { id -> "claude-opus-5-5".takeIf { id == LEAD } }),
        )
    }

    private fun rows(): Map<String, JsonObject> = Json.parseToJsonElement(routes.sessionsJson()).jsonObject
        .getValue("sessions").jsonArray
        .map { it.jsonObject }
        .associateBy { it.getValue("session_id").jsonPrimitive.content }

    @Test
    fun `with reading off a session reads no model from its conversation and names the one splice served it`() {
        val lead = rows().getValue(LEAD)

        assertEquals(JsonNull, lead["model"] ?: JsonNull, "reading off reads nothing from the conversation")
        assertEquals("claude-opus-5-5", lead.getValue("served_model").jsonPrimitive.content)
        assertFalse(rows().getValue(BUILDER).containsKey("served_model"), "a session splice never served names none")
    }

    @Test
    fun `the listing says reading is off, so the console shows no question a session waits on`() {
        val listing = Json.parseToJsonElement(routes.sessionsJson()).jsonObject

        assertEquals(false, listing.getValue("reading").jsonPrimitive.boolean)
    }

    @Test
    fun `Continue on keeps the served model as the one a move left`() {
        val lead = rig.registry.read().first { it.sessionId == LEAD }

        assertEquals("claude-opus-5-5", routes.modelOf(lead))
    }
}
