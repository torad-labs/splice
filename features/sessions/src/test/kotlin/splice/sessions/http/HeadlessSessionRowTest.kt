package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.HeadlessSessionSource
import splice.sessions.registry.ServedLaunchRows
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import java.nio.file.Files
import java.nio.file.Path

class HeadlessSessionRowTest {
    @Test
    fun `a served headless session appears once with its harness id and launch labels`(@TempDir dir: Path) {
        val routes = routes(dir) { listOf(launchRow(7, SessionAvailability.LIVE)) }

        val row = rows(routes).single()
        assertEquals(ID, row.field("session_id"))
        assertEquals(ID, row.field("id"))
        assertEquals("headless", row.field("kind"))
        assertEquals("7", row.field("pid"))
        assertEquals("/work", row.field("cwd"))
        assertEquals("live", row.field("availability"))
        assertEquals("""{"role":"worker"}""", row.getValue("labels").toString())
    }

    @Test
    fun `two serving processes share one row that stays live until both exit`(@TempDir dir: Path) {
        var first = SessionAvailability.LIVE
        var second = SessionAvailability.LIVE
        val routes = routes(dir) { listOf(launchRow(7, first), launchRow(8, second)) }

        assertEquals("live", rows(routes).single().field("availability"))
        first = SessionAvailability.GONE
        assertEquals("live", rows(routes).single().field("availability"))
        assertEquals("8", rows(routes).single().field("pid"))
        second = SessionAvailability.GONE
        assertEquals("gone", rows(routes).single().field("availability"))
    }

    @Test
    fun `a registry session keeps its own row and labels instead of a headless twin`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"$ID","cwd":"/interactive","kind":"interactive","updatedAt":$NOW}""",
        )
        val routes = routes(dir) { listOf(launchRow(7, SessionAvailability.LIVE)) }

        val row = rows(routes).single()
        assertEquals(ID, row.field("session_id"))
        assertEquals("/interactive", row.field("cwd"))
        assertEquals("""{"role":"interactive-worker"}""", row.getValue("labels").toString())
    }

    private fun routes(dir: Path, served: () -> List<SessionRecord>): SessionsRoutes {
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true }, clock = { NOW })
        val launches = object : ServedLaunchRows {
            override fun rows(): List<SessionRecord> = served()
        }
        return SessionsRoutes(
            HeadlessSessionSource(registry, launches),
            TestTranscripts(),
            facts = SessionRowFacts(labelsOf = SessionLabelsOf { mapOf("role" to "interactive-worker") }),
        )
    }

    private fun launchRow(pid: Long, availability: SessionAvailability) = SessionRecord(
        sessionId = ID,
        name = null,
        status = SessionStatus(),
        route = SessionRoute.Unknown,
        availability = availability,
        process = SessionProcess(pid, "/work", NOW, NOW, null),
        client = SessionClient("headless", null, launchLabels = mapOf("role" to "worker")),
    )

    private fun rows(routes: SessionsRoutes) = Json.parseToJsonElement(routes.sessionsJson())
        .jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }

    private fun JsonObject.field(key: String) = getValue(key).jsonPrimitive.content
}

private const val ID = "a934dca9-8a01-4dd2-aca0-1c976892b51e"
private const val NOW = 1_789_312_411_660L
