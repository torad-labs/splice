// NEW: V4-421 — /api/sessions says, per session, whether it has a transcript to resume. Found by the
// console's wire probe on the everyday daemon: it took the first listed session and the resume route
// answered 404 "the session is in no head's transcript tree". That row was a messaging bridge, which
// registers itself in Claude Code's session registry (a session id, a pid, a socket) and never writes a
// transcript, so the list carried a session nothing could resume. The rule is the resume route's: a
// regular file with conversation bytes in some head's own tree. The fixtures here are real files.
package splice.sessions.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.query.SessionHead
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Files
import java.nio.file.Path

private const val NOW = 1_789_312_411_660L
private const val WITH_CONVERSATION = "aaaaaaaa-0000-4000-8000-000000000001"
private const val EMPTY_FILE = "aaaaaaaa-0000-4000-8000-000000000002"
private const val NO_FILE = "aaaaaaaa-0000-4000-8000-000000000003"
private const val IN_OTHER_HEAD = "aaaaaaaa-0000-4000-8000-000000000004"

class ListedSessionResumableTest {
    @TempDir lateinit var tmp: Path

    private val asked = mutableListOf<String>()

    private fun tree(name: String): Path = Files.createDirectories(tmp.resolve(name))

    private fun transcript(tree: Path, id: String, bytes: String) {
        val project = Files.createDirectories(tree.resolve("projects/-work-repo"))
        Files.writeString(project.resolve("$id.jsonl"), bytes)
    }

    /** Claude Code's own layout, walked the way the real reader walks it: `<root>/projects/<slug>/<id>.jsonl`. */
    private fun onDisk() = TestTranscripts(
        pages = { id, roots, _, _ ->
            asked += id
            val hit = roots.map { it.resolve("projects") }.filter(Files::isDirectory)
                .flatMap { projects -> Files.list(projects).use { it.toList() } }
                .map { it.resolve("$id.jsonl") }
                .firstOrNull(Files::isRegularFile)
            if (hit == null) {
                TranscriptLookup.Missing(roots.map { it.resolve("projects").toString() })
            } else {
                TranscriptLookup.Found(TranscriptPage(id, hit.toString(), emptyList(), null, emptyMap()))
            }
        },
    )

    private fun registry(vararg ids: String): SessionRegistry {
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        ids.forEachIndexed { index, id ->
            Files.writeString(
                dir.resolve("${index + 1}.json"),
                """{"pid":${index + 1},"sessionId":"$id","cwd":"/work/repo","updatedAt":$NOW}""",
            )
        }
        return SessionRegistry(dir, routeOf = { SessionRoute.Direct }, pidAlive = { true }, clock = { NOW })
    }

    private fun rows(routes: SessionsRoutes): Map<String, JsonObject> =
        Json.parseToJsonElement(routes.sessionsJson()).jsonObject.getValue("sessions").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("session_id").jsonPrimitive.content }

    private fun heads(): Map<String, SessionHead> = mapOf(
        "codex" to SessionHead(transcriptRoot = tree("codex")),
        "grok" to SessionHead(transcriptRoot = tree("grok")),
    )

    private fun routes(
        heads: Map<String, SessionHead>,
        ids: Array<String>,
        viewEnabled: SessionTranscriptViewEnabled = SessionTranscriptViewEnabled { true },
    ) = SessionsRoutes(
        registry(*ids),
        onDisk(),
        roots = TranscriptRoots(heads, vanilla = tree("vanilla")),
        settings = TranscriptViewSettings(viewEnabled),
    )

    @Test
    fun `a session with a transcript resumes, and one that never wrote a file says it cannot`() {
        val heads = heads()
        transcript(heads.getValue("codex").transcriptRoot!!, WITH_CONVERSATION, """{"type":"user"}""" + "\n")
        transcript(heads.getValue("codex").transcriptRoot!!, EMPTY_FILE, "")
        val listed = rows(routes(heads, arrayOf(WITH_CONVERSATION, EMPTY_FILE, NO_FILE)))
        assertEquals("true", listed.getValue(WITH_CONVERSATION).getValue("resumable").jsonPrimitive.content)
        assertEquals(
            "false",
            listed.getValue(EMPTY_FILE).getValue("resumable").jsonPrimitive.content,
            "a file with no conversation bytes cannot be resumed",
        )
        assertEquals(
            "false",
            listed.getValue(NO_FILE).getValue("resumable").jsonPrimitive.content,
            "registered in Claude Code's registry, no transcript in any head's tree",
        )
    }

    @Test
    fun `a transcript in another head's tree counts, because the resume copies it across`() {
        val heads = heads()
        transcript(heads.getValue("grok").transcriptRoot!!, IN_OTHER_HEAD, """{"type":"user"}""" + "\n")
        val listed = rows(routes(heads, arrayOf(IN_OTHER_HEAD)))
        assertEquals("true", listed.getValue(IN_OTHER_HEAD).getValue("resumable").jsonPrimitive.content)
    }

    @Test
    fun `a transcript only in the vanilla tree is not claimed, since no head's tree holds it`() {
        val heads = heads()
        transcript(tmp.resolve("vanilla"), WITH_CONVERSATION, """{"type":"user"}""" + "\n")
        val listed = rows(routes(heads, arrayOf(WITH_CONVERSATION)))
        assertEquals("false", listed.getValue(WITH_CONVERSATION).getValue("resumable").jsonPrimitive.content)
    }

    @Test
    fun `with no head tree to search the list claims nothing about resuming`() {
        val listed = rows(routes(emptyMap(), arrayOf(WITH_CONVERSATION)))
        assertFalse(listed.getValue(WITH_CONVERSATION).containsKey("resumable"), "no tree asked, no claim made")
        assertTrue(asked.isEmpty(), "and nothing was read: $asked")
    }

    @Test
    fun `with the transcript view off no file is opened and the list claims nothing`() {
        val heads = heads()
        transcript(heads.getValue("codex").transcriptRoot!!, WITH_CONVERSATION, """{"type":"user"}""" + "\n")
        val listed = rows(routes(heads, arrayOf(WITH_CONVERSATION), SessionTranscriptViewEnabled { false }))
        assertFalse(listed.getValue(WITH_CONVERSATION).containsKey("resumable"))
        assertTrue(asked.isEmpty(), "the switch is consulted before any reader opens a file: $asked")
    }
}
