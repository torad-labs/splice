package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path

class SessionLabelsRowTest {
    @Test
    fun `the list contains labels on the interactive rows but does not add a headless row`(@TempDir dir: Path) {
        listOf("first", "second").forEachIndexed { index, id ->
            Files.writeString(dir.resolve("${index + 1}.json"), """{"pid":${index + 1},"sessionId":"$id"}""")
        }
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Unknown }, pidAlive = { true })
        val labels = mapOf("first" to mapOf("role" to "worker a"), "headless" to mapOf("role" to "worker b"))
        val routes = SessionsRoutes(
            registry,
            TestTranscripts(),
            facts = SessionRowFacts(labelsOf = SessionLabelsOf { labels[it.sessionId].orEmpty() }),
        )
        val rows = Json.parseToJsonElement(routes.sessionsJson()).jsonObject.getValue("sessions").jsonArray
            .map { it.jsonObject }.associateBy { it.getValue("session_id").jsonPrimitive.content }

        assertEquals(setOf("first", "second"), rows.keys)
        assertEquals("""{"role":"worker a"}""", rows.getValue("first").getValue("labels").toString())
        assertEquals("{}", rows.getValue("second").getValue("labels").toString())
    }
}
