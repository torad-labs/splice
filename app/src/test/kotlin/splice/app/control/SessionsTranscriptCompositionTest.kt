// NEW: LAYOUT-01 — the transcript route over the REAL Claude Code reader, on real bytes. The sessions
// feature owns the route and proves it against a fake reader; integrations/claude-code owns the reader.
// Only this module composes the two (ControlServer passes TranscriptReader to SessionsRoutes), so
// the end-to-end facts the pre-split test pinned live here: a headed session reads its own tree before
// the vanilla one, and the skipped-record counts come from the lines actually on disk.
package splice.app.control

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.transcript.TranscriptReader
import splice.sessions.http.SessionsRoutes
import splice.sessions.query.SessionHead
import splice.sessions.registry.SessionRegistry
import java.nio.file.Files
import java.nio.file.Path

private const val TRANSCRIPT_AT = 1_789_725_600_000L
private const val HEADED = "a1a1a1a1-0000-4000-8000-000000000001"
private const val HEADLESS = "b2b2b2b2-0000-4000-8000-000000000002"

class SessionsTranscriptCompositionTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** HEADED runs on the codex head (pid 11); HEADLESS is plain claude. */
    private fun registry(): SessionRegistry {
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        Files.writeString(dir.resolve("11.json"), """{"pid":11,"sessionId":"$HEADED","updatedAt":$TRANSCRIPT_AT}""")
        Files.writeString(dir.resolve("12.json"), """{"pid":12,"sessionId":"$HEADLESS","updatedAt":$TRANSCRIPT_AT}""")
        return SessionRegistry(
            sessionsDir = dir,
            headOf = { pid -> "codex".takeIf { pid == 11L } },
            pidAlive = { true },
            clock = { TRANSCRIPT_AT },
        )
    }

    /** One user line, one torn line, one sidechain record and one attachment, under [root]. */
    private fun write(root: Path, text: String): Path {
        val file = root.resolve("projects/-w/$HEADED.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"type":"user","message":{"role":"user","content":"$text"}}""" + "\n" +
                """not json""" + "\n" +
                """{"type":"user","isSidechain":true,"message":{"role":"user","content":"side"}}""" + "\n" +
                """{"type":"attachment"}""" + "\n",
        )
        return file
    }

    @Test
    fun `the real reader serves a headed session from its own tree and counts what it skipped`() {
        val own = tmp.resolve(".claude-codex")
        val vanilla = tmp.resolve(".claude")
        write(vanilla, "the vanilla copy")
        val file = write(own, "the head's copy")
        val routes = SessionsRoutes(
            registry(),
            TranscriptReader(),
            heads = mapOf("codex" to SessionHead(transcriptRoot = own)),
            vanilla = vanilla,
        )

        val reply = routes.transcript(HEADED, null, null)
        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(
            json(
                """{"session_id":"$HEADED","path":"$file","messages":[""" +
                    """{"index":0,"role":"user","text":"the head's copy"}],"next":null,""" +
                    """"unparseable_lines":1,"sidechain_records":1,"skipped_records":{"attachment":1}}""",
            ),
            json(reply.body),
        )

        val missing = routes.transcript(HEADLESS, null, null)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(
            listOf(vanilla.resolve("projects").toString(), own.resolve("projects").toString()),
            json(missing.body)["searched"]!!.jsonArray.map { it.jsonPrimitive.content },
            "a headless session searches vanilla first, then every head's own tree",
        )
        assertEquals(HttpStatusCode.BadRequest, routes.transcript(HEADED, "x", null).status)
    }
}
