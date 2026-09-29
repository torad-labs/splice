// NEW: V4-421 — the list and the resume route must not disagree about which sessions can be resumed:
// that disagreement is the defect (a listed session the resume route answered 404 for). The list's
// `resumable` and ResumeAcrossHeads.plan, the resolution the resume route and the launch both use, are
// asked here over the SAME trees on disk, through the real transcript reader. The trees include a head
// whose `projects` is a symlink into a shared one (four heads on the everyday daemon are), which is how a
// vanilla transcript reaches a head, and a vanilla tree no head links, which a resume does not search.
package splice.app.v4421

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.ResumePlan
import splice.client.transcript.TranscriptReader
import splice.sessions.http.SessionsRoutes
import splice.sessions.query.SessionHead
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
import java.nio.file.Path

private const val NOW = 1_789_312_411_660L
private const val OWN = "dddddddd-0000-4000-8000-000000000001"
private const val OTHER_HEAD = "dddddddd-0000-4000-8000-000000000002"
private const val THROUGH_LINK = "dddddddd-0000-4000-8000-000000000003"
private const val EMPTY = "dddddddd-0000-4000-8000-000000000004"
private const val ABSENT = "dddddddd-0000-4000-8000-000000000005"
private const val UNLINKED_VANILLA = "dddddddd-0000-4000-8000-000000000006"
private val ALL = listOf(OWN, OTHER_HEAD, THROUGH_LINK, EMPTY, ABSENT, UNLINKED_VANILLA)

class ListMatchesResumeTest {
    @TempDir lateinit var tmp: Path

    private fun tree(name: String): Path = Files.createDirectories(tmp.resolve(name))

    private fun transcript(tree: Path, id: String, bytes: String) {
        val project = Files.createDirectories(tree.resolve("projects/-work-repo"))
        Files.writeString(project.resolve("$id.jsonl"), bytes)
    }

    private val conversation = """{"type":"user","message":{"role":"user","content":"hi"}}""" + "\n"

    private val own by lazy { tree("codex") }
    private val other by lazy { tree("grok") }
    private val linked by lazy { tree("kimi") }
    private val shared by lazy { tree("shared") }

    private fun lay() {
        transcript(own, OWN, conversation)
        transcript(other, OTHER_HEAD, conversation)
        transcript(shared, THROUGH_LINK, conversation)
        Files.createSymbolicLink(linked.resolve("projects"), shared.resolve("projects"))
        transcript(own, EMPTY, "")
        transcript(tree("vanilla"), UNLINKED_VANILLA, conversation)
    }

    private fun listed(): Map<String, String> {
        val dir = Files.createDirectories(tmp.resolve("sessions"))
        ALL.forEachIndexed { index, id ->
            Files.writeString(
                dir.resolve("${index + 1}.json"),
                """{"pid":${index + 1},"sessionId":"$id","cwd":"/work/repo","updatedAt":$NOW}""",
            )
        }
        val registry = SessionRegistry(dir, routeOf = { SessionRoute.Direct }, pidAlive = { true }, clock = { NOW })
        val heads = mapOf(
            "codex" to SessionHead(transcriptRoot = own),
            "grok" to SessionHead(transcriptRoot = other),
            "kimi" to SessionHead(transcriptRoot = linked),
        )
        val routes = SessionsRoutes(registry, TranscriptReader(), heads, vanilla = tree("vanilla"))
        return Json.parseToJsonElement(routes.sessionsJson()).jsonObject.getValue("sessions").jsonArray
            .map { it.jsonObject }
            .associate {
                it.getValue("session_id").jsonPrimitive.content to it.getValue("resumable").jsonPrimitive.content
            }
    }

    /** What a launch (and the recipe route) would do for [id] on the head that owns [own]. */
    private fun resumes(id: String): Boolean =
        when (ResumeAcrossHeads().plan(own, listOf(other, linked), id)) {
            is ResumePlan.Owned, is ResumePlan.Copy -> true
            is ResumePlan.Empty, is ResumePlan.Absent, is ResumePlan.Invalid -> false
        }

    @Test
    fun `the list says resumable exactly where the resume plan resumes`() {
        lay()
        val list = listed()
        ALL.forEach { id -> assertEquals(resumes(id).toString(), list.getValue(id), "session ${id.takeLast(1)}") }
        assertEquals(
            listOf(OWN, OTHER_HEAD, THROUGH_LINK),
            ALL.filter { resumes(it) },
            "the fixture holds three sessions a resume takes and three it refuses",
        )
    }
}
