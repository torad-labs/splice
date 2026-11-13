// GET /api/sessions?gone=: a reader that sends the mark of the gone sessions' rows it holds is sent the sessions still
// here alone, and every row again once a gone row is added.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path

class SessionsGoneRowsTest {

    private val now = 1_789_312_411_660L

    @Test
    fun `a reader that holds the gone sessions is sent the ones still here, until another session is gone`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("11.json"),
            """{"pid":11,"sessionId":"s-11","status":"busy","updatedAt":${now - 5_000}}""",
        )
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"sessionId":"s-12","updatedAt":${now - 9_000}}""")
        val alive = mutableSetOf(11L)
        val registry = SessionRegistry(
            sessionsDir = dir,
            routeOf = { SessionRoute.Unknown },
            pidAlive = { it in alive },
            clock = { now },
        )
        val routes = SessionsRoutes(registry, TestTranscripts())
        fun read(held: String?): JsonObject = Json.parseToJsonElement(routes.sessionsJson(held)).jsonObject
        fun ids(body: JsonObject) =
            body["sessions"]!!.jsonArray.map { it.jsonObject["session_id"]!!.jsonPrimitive.content }
        fun mark(body: JsonObject) = body["gone_mark"]!!.jsonPrimitive.content

        // No mark: every row, and the mark of the gone ones.
        val whole = read(null)
        assertEquals(listOf("s-11", "s-12"), ids(whole))
        assertEquals(null, whole["gone_held"])

        // The mark sent back: the session still here alone, and the answer says the gone rows were left out.
        val rest = read(mark(whole))
        assertEquals(listOf("s-11"), ids(rest))
        assertEquals(true, rest["gone_held"]?.jsonPrimitive?.boolean)
        assertEquals(mark(whole), mark(rest))

        // s-11 ends. The gone rows are no longer the ones the reader holds: every row again, under another mark.
        alive.clear()
        val changed = read(mark(whole))
        assertEquals(listOf("s-11", "s-12"), ids(changed))
        assertEquals(null, changed["gone_held"])
        assertNotEquals(mark(whole), mark(changed))
    }
}
