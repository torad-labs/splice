package splice.sessions.list

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path

class SessionsJsonTest {
    private val now = 1_789_312_411_660L

    /** The kernel's start time of each running pid, as this machine's /proc would give it. */
    private class KernelStarts(private val starts: Map<Long, String>) : PidIdentity {
        override fun hostDomain(): String? = null

        override fun procStart(pid: Long): String? = starts[pid]

        override fun startedAt(pid: Long): Long? = null
    }

    @Test
    fun `each registration is one object with its identity, its state and how it was judged`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"aaaa1111-0000-4000-8000-000000000001","name":"alpha","status":"busy",""" +
                """"statusUpdatedAt":${now - 60_000},"updatedAt":${now - 120_000},"startedAt":${now - 900_000},""" +
                """"cwd":"/w/a","procStart":"4242","kind":"interactive","version":"2.1.0","peerProtocol":1,""" +
                """"messagingSocketPath":"/run/user/1000/cc-socks/11.sock"}""",
        )
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"status":"idle","updatedAt":${now - 5_000_000}}""")
        Files.writeString(dir.resolve("13.json"), """{"pid":13,"name":"gamma","updatedAt":$now}""")
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { pid -> if (pid == 11L) SessionRoute.Head("claudex") else SessionRoute.Direct },
            pidAlive = { it != 13L },
            clock = { now },
            identity = KernelStarts(mapOf(11L to "4242")),
        )
        val lines = mutableListOf<String>()

        val ok = SessionsJson(TerminalOutput { lines += it }).print(registry)

        assertTrue(ok)
        assertEquals(1, lines.size, "one document, on one line: $lines")
        val document = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals(JsonNull, document["error"])
        val sessions = document.getValue("sessions").jsonArray.map { it.jsonObject }.associateBy { it.pid() }
        val alpha = sessions.getValue(11L)
        assertEquals("aaaa1111-0000-4000-8000-000000000001", alpha.text("sessionId"))
        assertEquals("alpha", alpha.text("name"))
        assertEquals("live", alpha.text("availability"))
        assertEquals("busy", alpha.text("status"))
        assertEquals("claudex", alpha.text("head"))
        assertEquals("4242", alpha.text("procStart"))
        assertEquals("uds:/run/user/1000/cc-socks/11.sock", alpha.text("address"))
        assertEquals("/run/user/1000/cc-socks/11.sock", alpha.text("socket"))
        assertEquals(1L, alpha.getValue("peerProtocol").jsonPrimitive.long)
        assertEquals(now - 60_000, alpha.getValue("statusUpdatedAt").jsonPrimitive.long)
        assertEquals(dir.toString(), alpha.text("registryDir"))
        assertEquals("stale", sessions.getValue(12L).text("availability"))
        assertEquals(JsonNull, sessions.getValue(12L)["name"])
        assertEquals("direct", sessions.getValue(12L).text("route"))
        assertEquals("gone", sessions.getValue(13L).text("availability"))
    }

    @Test
    fun `a registry that cannot be listed says why and lists nothing`(@TempDir dir: Path) {
        val notADirectory = Files.writeString(dir.resolve("sessions"), "a file where the folder should be")
        val registry = SessionRegistry(notADirectory, { SessionRoute.Unknown }, clock = { now })
        val lines = mutableListOf<String>()

        val ok = SessionsJson(TerminalOutput { lines += it }).print(registry)

        val document = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals(false, ok)
        assertEquals(0, document.getValue("sessions").jsonArray.size)
        assertTrue(document.text("error").orEmpty().contains(notADirectory.toString()), "$document")
    }

    private fun JsonObject.pid(): Long = getValue("pid").jsonPrimitive.long

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
