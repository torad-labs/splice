// /api/sessions (v0.4.0, FEATURES.md §4): the registry as JSON with the availability derived, the
// head named or "unknown head", the address carried, and the headless note.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
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
            routeOf = { pid -> if (pid == 11L) SessionRoute.Head("claudex") else SessionRoute.Unknown },
            pidAlive = { it == 11L },
            clock = { now },
        )
        val body = Json.parseToJsonElement(SessionsRoutes(registry, TestTranscripts()).sessionsJson()).jsonObject
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

    // `route` tells apart the two facts `head` folds into "unknown head": a session that never went
    // through splice, and one splice cannot place. The value is the registry's, read once from the
    // process environment; a GONE pid is never read, so its route is unknown whatever it would say.
    @Test
    fun `route names head, direct and unknown while head keeps its old value`(@TempDir dir: Path) {
        listOf(21, 22, 23, 24).forEach { pid ->
            Files.writeString(dir.resolve("$pid.json"), """{"pid":$pid,"updatedAt":${now - 5_000}}""")
        }
        val routes = mapOf(21L to SessionRoute.Head("codex"), 22L to SessionRoute.Direct, 24L to SessionRoute.Direct)
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { pid -> routes[pid] ?: SessionRoute.Unknown },
            pidAlive = { it != 24L },
            clock = { now },
        )
        val body = Json.parseToJsonElement(SessionsRoutes(registry, TestTranscripts()).sessionsJson()).jsonObject
        val byPid = body["sessions"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["pid"]!!.jsonPrimitive.content }
        fun field(pid: Int, key: String) = byPid.getValue("$pid")[key]?.jsonPrimitive?.content

        assertEquals("head", field(21, "route"))
        assertEquals("codex", field(21, "head"))
        assertEquals("direct", field(22, "route"), "read, and no SPLICE=1: it talks to its provider itself")
        assertEquals("unknown head", field(22, "head"), "head is unchanged")
        assertEquals("unknown", field(23, "route"), "unreadable, or a splice launch no head owns")
        assertEquals("unknown head", field(23, "head"))
        assertEquals("unknown", field(24, "route"), "a gone pid's environment is never read")
        assertEquals("gone", field(24, "availability"))
    }

    @Test
    fun `a registry directory that cannot be listed carries an error beside the empty list`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("sessions"), "not a directory")
        val registry = SessionRegistry(sessionsDir = file, routeOf = { SessionRoute.Unknown }, clock = { now })
        val body = Json.parseToJsonElement(SessionsRoutes(registry, TestTranscripts()).sessionsJson()).jsonObject
        assertTrue(body["sessions"]!!.jsonArray.isEmpty())
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("sessions"), body.toString())
    }
}
