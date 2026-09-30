// NEW: V4-444 — session cards get a compact activity projection without opening transcript pages.
package splice.sessions.v4444

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.http.SessionsRoutes
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path

class SessionsActivityTest {
    @Test
    fun `a row carries the one line role tool text and epoch milliseconds`(@TempDir tmp: Path) {
        val source = LastSource(
            TranscriptMessage(0, TranscriptRole.ASSISTANT, 7L, "   redacted\n\t" + "x ".repeat(120)),
        )
        val last = row(tmp, source).getValue("last").jsonObject
        assertEquals("assistant", last.getValue("role").jsonPrimitive.content)
        assertEquals(JsonNull, last.getValue("tool"))
        assertEquals("7", last.getValue("ts").jsonPrimitive.content)
        val text = last.getValue("text").jsonPrimitive.content
        assertTrue(text.startsWith("redacted x x "))
        assertEquals(160, text.length)
        assertFalse(text.any { it == '\n' || it == '\t' })
        assertEquals(1, source.calls)
        assertEquals(listOf(tmp.resolve("vanilla")), source.roots)
    }

    @Test
    fun `a call is its tool and its description, even when a long command comes first`(@TempDir tmp: Path) {
        val command = "cd /home/marcos/Documents/dev/projects/mythos/repo && " + "./gradlew check ".repeat(20)
        val input = """{"command":"$command","description":"Run every check in the repo","timeout":120000}"""
        val last = row(tmp, LastSource(call("Bash", input))).getValue("last").jsonObject
        assertEquals("Bash", last.getValue("tool").jsonPrimitive.content)
        assertEquals("Run every check in the repo", last.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `a call with no description names its target and never its command or path`(@TempDir tmp: Path) {
        fun text(tool: String, input: String): String {
            val last = row(tmp, LastSource(call(tool, input))).getValue("last").jsonObject
            return last.getValue("text").jsonPrimitive.content
        }
        val pattern = "TurnWiring(" + "$" + "$" + "$" + "ARGS)"
        assertEquals("", text("Bash", """{"command":"cd /home/marcos/secret && ls"}"""))
        assertEquals("sessions.ts", text("Edit", """{"file_path":"/home/marcos/repo/src/lib/sessions.ts"}"""))
        assertEquals(pattern, text("mcp__ast-grep__find_code", """{"pattern":"$pattern"}"""))
        assertEquals("kotlin coroutines", text("WebSearch", """{"query":"kotlin coroutines"}"""))
        assertEquals("example.com", text("WebFetch", """{"url":"https://example.com/a/b?x=1"}"""))
        assertEquals("", text("Bash", "{not json"))
    }

    @Test
    fun `an ask-the-user call carries its question`(@TempDir tmp: Path) {
        val input = """{"questions":[{"question":"Which plan should take the session?","header":"Plan"}]}"""
        val last = row(tmp, LastSource(call("AskUserQuestion", input))).getValue("last").jsonObject
        assertEquals("Which plan should take the session?", last.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `what a person or the model said is passed through as text`(@TempDir tmp: Path) {
        val said = TranscriptMessage(0, TranscriptRole.ASSISTANT, 7L, "I built it. {\"command\":\"x\"}")
        val last = row(tmp, LastSource(said)).getValue("last").jsonObject
        assertEquals("I built it. {\"command\":\"x\"}", last.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `off missing id and absent transcript publish no activity and off never reads`(@TempDir tmp: Path) {
        val source = LastSource(null)
        assertEquals(JsonNull, row(tmp, source, enabled = false)["last"])
        assertEquals(0, source.calls)
        assertEquals(JsonNull, row(tmp, source, id = null)["last"])
        assertEquals(0, source.calls)
        assertEquals(JsonNull, row(tmp, source)["last"])
        assertEquals(1, source.calls)
    }

    private fun row(
        tmp: Path,
        source: LastSource,
        enabled: Boolean = true,
        id: String? = "synthetic-activity",
    ): JsonObject {
        val record = SessionRecord(
            pid = null, sessionId = id, cwd = null, name = null, kind = null, version = null,
            status = null, statusUpdatedAt = null, startedAt = null, updatedAt = null, messagingSocketPath = null,
            route = SessionRoute.Unknown, availability = SessionAvailability.LIVE,
        )
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> = listOf(record)
            override fun list(): SessionListing = SessionListing(read())
        }
        val route = SessionsRoutes(
            registry,
            source,
            vanilla = tmp.resolve("vanilla"),
            viewEnabled = SessionTranscriptViewEnabled { enabled },
        )
        val body = Json.parseToJsonElement(route.sessionsJson()).jsonObject
        return body.getValue("sessions").jsonArray.single().jsonObject
    }

    private fun call(tool: String, input: String) =
        TranscriptMessage(0, TranscriptRole.ASSISTANT, 7L, input, tool, result = false)

    private class LastSource(private val message: TranscriptMessage?) : SessionTranscripts {
        var calls = 0
        var roots = emptyList<Path>()
        override fun last(sessionId: String, roots: List<Path>): TranscriptMessage? {
            calls += 1
            this.roots = roots
            return message
        }
        override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup =
            error("activity must never fetch a transcript page")
        override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts =
            error("activity must never scan sent text")
    }
}
