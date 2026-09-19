// /api/sessions (v0.4.0, FEATURES.md §4): the registry as JSON with the availability derived, the
// head named or "unknown head", the address carried, and the headless note.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.api.SessionsRoutes
import splice.core.sessions.SessionRegistry
import java.nio.file.Files
import java.nio.file.Path

class SessionsRoutesTest {

    private val now = 1_789_312_411_660L

    @Test
    fun `sessions json carries availability, head, address and the headless note`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"s-11","name":"alpha","status":"busy","updatedAt":${now - 5_000},""" +
                """"cwd":"/w/a","messagingSocketPath":"/run/user/1000/cc-socks/11.sock"}""",
        )
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"updatedAt":${now - 5_000}}""")
        val registry = SessionRegistry(
            sessionsDir = dir,
            headOf = { pid -> "claudex".takeIf { pid == 11L } },
            pidAlive = { it == 11L },
            clock = { now },
        )
        val body = Json.parseToJsonElement(SessionsRoutes(registry).sessionsJson()).jsonObject
        assertTrue(body["note"]!!.jsonPrimitive.content.contains("claude -p"))
        val rows = body["sessions"]!!.jsonArray.map { it.jsonObject }
        val alpha = rows.first { it["name"]?.jsonPrimitive?.content == "alpha" }
        assertEquals("claudex", alpha["head"]?.jsonPrimitive?.content)
        assertEquals("live", alpha["availability"]?.jsonPrimitive?.content)
        assertEquals("uds:/run/user/1000/cc-socks/11.sock", alpha["address"]?.jsonPrimitive?.content)
        assertEquals("s-11", alpha["session_id"]?.jsonPrimitive?.content)
        val bare = rows.first { it["pid"]?.jsonPrimitive?.content == "12" }
        assertEquals("unknown head", bare["head"]?.jsonPrimitive?.content)
        assertEquals("gone", bare["availability"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a registry directory that cannot be listed carries an error beside the empty list`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("sessions"), "not a directory")
        val registry = SessionRegistry(sessionsDir = file, headOf = { null }, clock = { now })
        val body = Json.parseToJsonElement(SessionsRoutes(registry).sessionsJson()).jsonObject
        assertTrue(body["sessions"]!!.jsonArray.isEmpty())
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("sessions"), body.toString())
    }
}
