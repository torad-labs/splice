// NEW: V4-169 — the receiving end of the resume hook, pinned on its decision (ResumeHookRoute.handle)
// the way SessionsRoutes is: the transcript named by a resume of a session this head reads is moved
// onto the head's model; everything else is refused in one sentence and rewrites nothing. The path
// check is the security half: the hook's bearer can only ever reach transcripts under the head's own
// projects tree, resolved with symlinks followed — because under V4-168 that dir IS a link.
package splice.control

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.api.ResumeHookRoute
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.launch.ClaudePolicy
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "0f6b1c2e-7d3a-4b8e-9c1d-2a5f6e7b8c9d"
private const val PINNED = "gpt-5.6-sol"
private const val FOREIGN_ROW = """{"type":"assistant","sessionId":"$SESSION","message":{"model":"k3-256k","content":[]}}"""

class ResumeHookRouteTest {

    private val log = StringBuilder()

    private fun route(own: Path): ResumeHookRoute {
        val heads = mapOf("codex" to head(own))
        return ResumeHookRoute(heads, log = { log.append(it) })
    }

    private fun hookJson(sessionId: String, transcript: Path, source: String = "resume"): String = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
        put("transcript_path", JsonPrimitive(transcript.toString()))
        put("source", JsonPrimitive(source))
        put("hook_event_name", JsonPrimitive("SessionStart"))
    }.toString()

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** A head whose projects dir is the V4-168 link into a shared tree — the operator's real shape. */
    private fun linkedHead(home: Path): Pair<Path, Path> {
        val shared = Files.createDirectories(home.resolve(".claude").resolve("projects"))
        val own = Files.createDirectories(home.resolve(".claude-codex"))
        Files.createSymbolicLink(own.resolve("projects"), shared)
        return own to shared
    }

    @Test
    fun `a resume of a session in the shared tree is moved onto the head's model through the link`(
        @TempDir home: Path,
    ) {
        val (own, shared) = linkedHead(home)
        val transcript = write(shared.resolve("-work-repo").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")

        // The hook names the path as Claude Code sees it: through the head's own projects dir.
        val claimed = own.resolve("projects").resolve("-work-repo").resolve("$SESSION.jsonl")
        assertNull(route(own).handle("codex", hookJson(SESSION, claimed)))

        assertTrue(Files.readString(transcript).contains("\"$PINNED\""), Files.readString(transcript))
        assertTrue(log.contains("1 assistant rows moved onto $PINNED"), log.toString())
    }

    @Test
    fun `a transcript outside the head's tree is refused, even when it is real and named exactly`(
        @TempDir home: Path,
    ) {
        val (own, _) = linkedHead(home)
        val elsewhere = write(home.resolve("elsewhere").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")
        val lookalike = write(home.resolve(".claude/projects-not").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")

        assertEquals(
            "the transcript path is not a file under this head's transcript tree",
            route(own).handle("codex", hookJson(SESSION, elsewhere)),
        )
        assertEquals(
            "the transcript path is not a file under this head's transcript tree",
            route(own).handle("codex", hookJson(SESSION, lookalike)),
            "a sibling whose name merely starts with the tree's text is outside it",
        )
        assertEquals(FOREIGN_ROW + "\n", Files.readString(elsewhere), "nothing outside the tree is ever written")
        assertEquals(FOREIGN_ROW + "\n", Files.readString(lookalike))
    }

    @Test
    fun `only a resume from a known head with a session-shaped id does anything`(@TempDir home: Path) {
        val (own, shared) = linkedHead(home)
        val transcript = write(shared.resolve("-work-repo").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")
        val route = route(own)

        assertEquals("no head is keyed that", route.handle("nope", hookJson(SESSION, transcript)))
        assertEquals("the hook source is not a resume", route.handle("codex", hookJson(SESSION, transcript, "startup")))
        assertEquals("the session id is not a session id", route.handle("codex", hookJson("../../etc", transcript)))
        assertEquals("the body is not a JSON object", route.handle("codex", "not json"))
        assertEquals(FOREIGN_ROW + "\n", Files.readString(transcript), "every refusal leaves the transcript as it was")
    }

    private fun head(own: Path): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "codex"
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        launchSpec = LaunchSpec(
            trees = HeadTrees(own),
            pinnedModel = PINNED,
            availableModelIds = listOf(PINNED),
            modelLabels = mapOf(PINNED to PINNED),
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
