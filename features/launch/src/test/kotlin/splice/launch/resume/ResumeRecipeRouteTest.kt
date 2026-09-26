// NEW: V4-320 — the resume recipe pinned on its decision (ResumeRecipeRoute.answer): a session in
// another head's tree reads as the copy the launch would make, one in the head's own tree as a resume in
// place, and every refusal is one sentence with its status. READ ONLY: every recipe leaves both trees as
// they were, because the act is the operator's `<command> -r <id>` through /launch.
package splice.launch.resume

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.core.head.Head
import splice.http.JsonReply
import splice.launch.HeadTrees
import splice.launch.LaunchHead
import splice.launch.LaunchSpec
import splice.launch.absentAuth
import splice.launch.launchHeadsOf
import splice.launch.runningHead
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "3c4d5e6f-0000-4000-8000-000000000320"
private const val PINNED = "gpt-6-sol"

/** The head's wrapper command, distinct from its key: the recipe runs the command, never the key. */
private const val COMMAND = "claudex"

class ResumeRecipeRouteTest {

    @TempDir
    lateinit var home: Path

    private val asked = mutableListOf<String>()

    private val own: Path get() = home.resolve("codex")
    private val sibling: Path get() = home.resolve("grok")

    private fun route(
        linked: Boolean = true,
        live: Boolean? = true,
        vararg extra: LaunchHead,
    ): ResumeRecipeRoute = ResumeRecipeRoute(
        launchHeadsOf(codex(), *extra),
        live = SessionLive { live },
        linked = WrapperLinked { command ->
            asked += command
            linked
        },
    )

    private fun transcript(tree: Path): Path {
        val file = tree.resolve("projects").resolve("-work-repo").resolve("$SESSION.jsonl")
        Files.createDirectories(file.parent)
        return Files.writeString(file, "{}\n")
    }

    private fun json(reply: JsonReply) = Json.parseToJsonElement(reply.body).jsonObject

    private fun error(text: String) = buildJsonObject { put("error", text) }

    /** Every path under [home], a file with its bytes: what a read-only answer must leave as it found. */
    private fun files(): Map<Path, String> = Files.walk(home).use { paths ->
        paths.toList().associateWith { if (Files.isRegularFile(it)) Files.readString(it) else "dir" }
    }

    @Test
    fun `a session in another head's tree reads as the copy the launch makes, and copies nothing`() {
        val from = transcript(sibling)
        Files.createDirectories(own.resolve("projects"))
        val before = files()
        val tree = own.resolve("projects").resolve("-work-repo")

        val reply = route().answer(SESSION, "codex")

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertEquals(
            Json.parseToJsonElement(
                """{"session_id":"$SESSION","head":"codex","argv":["$COMMAND","-r","$SESSION"],
                |"from":"$from","to_tree":"$tree","copies":true,
                |"model":"$PINNED","live":true}
                """.trimMargin(),
            ),
            json(reply),
        )
        assertEquals(before, files(), "a recipe writes nothing")
        assertEquals(listOf(COMMAND), asked, "the link asked about is the head's command")
    }

    @Test
    fun `a session in the head's own tree resumes in place`() {
        val owned = transcript(own)
        transcript(sibling)
        val before = files()

        val reply = json(route(live = false).answer(SESSION, "codex"))

        assertEquals(owned.toString(), reply["from"]?.toString()?.trim('"'))
        assertEquals(owned.parent.toString(), reply["to_tree"]?.toString()?.trim('"'))
        assertEquals("false", reply["copies"].toString())
        assertEquals("false", reply["live"].toString())
        assertEquals(before, files(), "a recipe rewrites no model in place either")
    }

    @Test
    fun `with no registry wired, whether the session runs is null, never false`() {
        transcript(own)
        assertEquals("null", json(route(live = null).answer(SESSION, "codex"))["live"].toString())
    }

    @Test
    fun `an unlinked command is refused with the fix, before any tree is read`() {
        val reply = route(linked = false).answer("not a session id", "codex")

        assertEquals(HttpStatusCode.Conflict, reply.status)
        assertEquals(error("The $COMMAND command is not linked; run splice install codex."), json(reply))
    }

    @Test
    fun `a head not named, not configured, or not launchable is refused by name`() {
        val unlaunchable = LaunchHead(runningHead("vanilla"), absentAuth("test"), spec = null)
        val route = route(true, true, unlaunchable)

        assertEquals(HttpStatusCode.BadRequest, route.answer(SESSION, null).status)
        assertEquals(HttpStatusCode.BadRequest, route.answer(SESSION, " ").status)
        for (key in listOf("kimi", "vanilla")) {
            val reply = route.answer(SESSION, key)
            assertEquals(HttpStatusCode.NotFound, reply.status)
            assertEquals(error("no launchable head is keyed '$key' (launchable: codex)"), json(reply))
        }
        assertEquals(emptyList<String>(), asked, "no link is checked for a head that is not there")
    }

    @Test
    fun `a session in no tree is refused naming the trees searched, and a bad id without echoing it`() {
        Files.createDirectories(own.resolve("projects"))
        Files.createDirectories(sibling.resolve("projects"))

        val absent = route().answer(SESSION, "codex")
        assertEquals(HttpStatusCode.NotFound, absent.status)
        assertEquals(error("the session is in no head's transcript tree (searched: codex, grok)"), json(absent))

        val invalid = route().answer("../../etc/passwd", "codex")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(false, invalid.body.contains("passwd"), invalid.body)
    }

    private fun codex(): LaunchHead = LaunchHead(
        head = object : Head by runningHead("codex") {
            override val label: String = COMMAND
        },
        auth = absentAuth("test"),
        spec = LaunchSpec(
            trees = HeadTrees(own, siblings = listOf(sibling)),
            pinnedModel = PINNED,
            availableModelIds = listOf(PINNED),
            modelLabels = mapOf(PINNED to "Sol"),
            contextWindow = 1_000,
            modelOptionsCache = buildJsonObject { },
            statuslineCommand = "",
            loginCommand = "",
            signInLabel = "",
            policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
            port = 0,
            inferenceToken = "t",
            apiTimeoutMs = 1_000,
        ),
    )
}
