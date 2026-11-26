// A headless session that has served a turn is one row on GET /api/sessions, however many processes served it,
// and it carries the labels that launch recorded. One that has not served a turn is absent.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.ServedLaunch
import splice.sessions.registry.ServedLaunches
import splice.sessions.registry.ServedSessionSource
import splice.sessions.registry.ServedSessions
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path

class HeadlessSessionRowTest {
    @Test
    fun `a served headless session is one row with its labels, and an unserved launch is not`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("11.json"), """{"pid":11,"sessionId":"interactive","updatedAt":1}""")
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true }, clock = { NOW })
        val launches = mapOf(
            "first" to ServedLaunch(mapOf("role" to "worker-a"), "/work", 7L),
            "second" to ServedLaunch(mapOf("role" to "worker-b"), "/work", 8L),
            "idle" to ServedLaunch(mapOf("role" to "never"), "/work", 9L),
        )
        val served = ServedSessions(
            clock = { NOW },
            alive = { it == 7L },
            launches = ServedLaunches { launches[it] },
        )
        served.note("headless", "claudex", "first", served = true)
        served.note("headless", "claudex", "second", served = true)
        served.note("waiting", null, "idle", served = false)
        val routes = SessionsRoutes(
            ServedSessionSource(registry) { served },
            TestTranscripts(),
            facts = SessionRowFacts(labelsOf = ServedLabels { served }),
        )

        val rows = Json.parseToJsonElement(routes.sessionsJson()).jsonObject.getValue("sessions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("session_id").jsonPrimitive.content }

        assertEquals(setOf("interactive", "headless"), rows.keys)
        val headless = rows.getValue("headless")
        assertEquals("""{"role":"worker-b"}""", headless.getValue("labels").toString())
        assertEquals("claudex", headless.getValue("head").jsonPrimitive.content)
        assertEquals("live", headless.getValue("availability").jsonPrimitive.content)
        assertEquals("/work", headless.getValue("cwd").jsonPrimitive.content)
        assertEquals("{}", rows.getValue("interactive").getValue("labels").toString())
    }
}

private const val NOW = 1_789_312_411_660L
