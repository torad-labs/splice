// NEW: V4-169 — the receiving end of the resume hook, pinned on its decision (ResumeHookRoute.handle)
// the way SessionsRoutes is: the transcript named by a resume of a session this head reads is moved
// onto the head's model; everything else is refused in one sentence and rewrites nothing. The path
// check is the security half: the hook's bearer can only ever reach transcripts under the head's own
// projects tree, resolved with symlinks followed — because under V4-168 that dir IS a link.
package splice.launch.resume

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.client.resume.SessionOwnership
import splice.launch.HeadTrees
import splice.launch.LaunchHead
import splice.launch.LaunchSpec
import splice.launch.absentAuth
import splice.launch.launchHeadsOf
import splice.launch.runningHead
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "0f6b1c2e-7d3a-4b8e-9c1d-2a5f6e7b8c9d"
private const val OTHER = "1a2b3c4d-0000-4000-8000-000000000000"
private const val PINNED = "gpt-5.6-sol"
private const val SERVED = "gpt-6-astra"
private const val FOREIGN_ROW = """{"type":"assistant","sessionId":"$SESSION","message":{"model":"k3-256k","content":[]}}"""

class ResumeHookRouteTest {

    private val log = StringBuilder()

    private fun route(own: Path, served: List<String> = listOf(PINNED)): ResumeHookRoute {
        return ResumeHookRoute(launchHeadsOf(head(own, served)), log = { log.append(it) })
    }

    private fun hookJson(
        sessionId: String,
        transcript: Path,
        source: String = "resume",
        cwd: String? = null,
    ): String = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
        put("transcript_path", JsonPrimitive(transcript.toString()))
        put("source", JsonPrimitive(source))
        put("hook_event_name", JsonPrimitive("SessionStart"))
        cwd?.let { put("cwd", JsonPrimitive(it)) }
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

    // v0.4.0 review round 2: the route hands the rewrite the head's whole roster, so a resumed row on a
    // model this head serves (claude-splice's opus beside its pinned fable) is left exactly as it is.
    @Test
    fun `a resume leaves rows on a model the head serves and moves only foreign ones`(@TempDir home: Path) {
        val (own, shared) = linkedHead(home)
        val servedRow = FOREIGN_ROW.replace("k3-256k", SERVED)
        val transcript = write(shared.resolve("-work-repo").resolve("$SESSION.jsonl"), "$servedRow\n$FOREIGN_ROW\n")
        val claimed = own.resolve("projects").resolve("-work-repo").resolve("$SESSION.jsonl")

        assertNull(route(own, served = listOf(PINNED, SERVED)).handle("codex", hookJson(SESSION, claimed)))

        val rows = Files.readString(transcript).lines()
        assertEquals(servedRow, rows[0], "a served model's row is restored by Claude Code as it is")
        assertTrue(rows[1].contains("\"$PINNED\""), rows[1])
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
        assertEquals(
            "the hook source is neither a startup nor a resume",
            route.handle("codex", hookJson(SESSION, transcript, "compact")),
        )
        assertEquals("the session id is not a session id", route.handle("codex", hookJson("../../etc", transcript)))
        assertEquals("the body is not a JSON object", route.handle("codex", "not json"))
        assertEquals(FOREIGN_ROW + "\n", Files.readString(transcript), "every refusal leaves the transcript as it was")
    }

    // V4-183: both sources enter the session in this head's own index; only a resume rewrites.
    @Test
    fun `a startup records the session as this head's, in its cwd, and rewrites nothing`(@TempDir home: Path) {
        val (own, shared) = linkedHead(home)
        val transcript = write(shared.resolve("-work-repo").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")
        val cwd = Files.createDirectories(home.resolve("work-repo")).toString()

        assertNull(route(own).handle("codex", hookJson(SESSION, transcript, "startup", cwd)))

        assertEquals(SESSION, SessionOwnership(own).newestFor(cwd)?.id, "the head owns the session it started")
        assertEquals(FOREIGN_ROW + "\n", Files.readString(transcript), "a startup has nothing to move")
        assertFalse(log.contains("moved onto"), log.toString())
    }

    // 2026-09-21: the startup hook fires BEFORE Claude Code writes the session's first row. The live
    // daemon refused every startup at "not a file under this head's transcript tree", so no head ever
    // owned a session and a bare -c kept crossing into whatever session was newest in the cwd.
    @Test
    fun `a startup owns a session whose transcript is not written yet, a resume still needs the file`(
        @TempDir home: Path,
    ) {
        val (own, shared) = linkedHead(home)
        val dir = Files.createDirectories(shared.resolve("-work-repo"))
        val unwritten = dir.resolve("$SESSION.jsonl")
        val cwd = Files.createDirectories(home.resolve("work-repo")).toString()

        assertNull(route(own).handle("codex", hookJson(SESSION, unwritten, "startup", cwd)))
        assertFalse(Files.exists(unwritten), "the route never creates the transcript")
        // newestFor only answers once the transcript exists — Claude Code's first write.
        assertNull(SessionOwnership(own).newestFor(cwd))
        write(unwritten, FOREIGN_ROW + "\n")
        assertEquals(SESSION, SessionOwnership(own).newestFor(cwd)?.id, "owned from its startup on")

        // Still refused on a startup: a name that is not this session's, a directory outside the
        // tree, and a path whose parent does not exist. A resume of an unwritten file is refused too.
        val refusal = "the transcript path is not a file under this head's transcript tree"
        val misnamed = dir.resolve("not-$OTHER.jsonl")
        assertEquals(refusal, route(own).handle("codex", hookJson(OTHER, misnamed, "startup", cwd)))
        val outside = Files.createDirectories(home.resolve("elsewhere")).resolve("$OTHER.jsonl")
        assertEquals(refusal, route(own).handle("codex", hookJson(OTHER, outside, "startup", cwd)))
        val noParent = shared.resolve("-never-made").resolve("$OTHER.jsonl")
        assertEquals(refusal, route(own).handle("codex", hookJson(OTHER, noParent, "startup", cwd)))
        val unwrittenOther = dir.resolve("$OTHER.jsonl")
        assertEquals(refusal, route(own).handle("codex", hookJson(OTHER, unwrittenOther, "resume", cwd)))
        val index = Files.readString(own.resolve("splice-sessions.json"))
        assertFalse(index.contains(OTHER), "no refusal recorded a session")
    }

    // astra, PR #169: Path.of("\u0000") throws InvalidPathException; the startup fallback must
    // refuse it like any other unresolvable path, never throw past the route's always-200 answer.
    @Test
    fun `a startup whose path the filesystem cannot spell is refused, not thrown`(@TempDir home: Path) {
        val (own, _) = linkedHead(home)
        val cwd = Files.createDirectories(home.resolve("work-repo")).toString()
        val body = """{"session_id":"$SESSION","transcript_path":"\u0000","source":"startup","cwd":"$cwd"}"""
        assertEquals(
            "the transcript path is not a file under this head's transcript tree",
            route(own).handle("codex", body),
        )
        assertNull(SessionOwnership(own).newestFor(cwd))
        assertFalse(Files.exists(own.resolve("splice-sessions.json")), "nothing was recorded")
    }

    @Test
    fun `a resume records the session too, and a hook without a cwd records nothing`(@TempDir home: Path) {
        val (own, shared) = linkedHead(home)
        val transcript = write(shared.resolve("-work-repo").resolve("$SESSION.jsonl"), FOREIGN_ROW + "\n")
        val cwd = Files.createDirectories(home.resolve("work-repo")).toString()

        assertNull(route(own).handle("codex", hookJson(SESSION, transcript, "resume", cwd)))
        assertEquals(SESSION, SessionOwnership(own).newestFor(cwd)?.id)
        assertTrue(Files.readString(transcript).contains("\"$PINNED\""), "a resume still rewrites")

        val other = write(shared.resolve("-elsewhere").resolve("$OTHER.jsonl"), FOREIGN_ROW + "\n")
        val elsewhere = Files.createDirectories(home.resolve("elsewhere")).toString()
        assertNull(route(own).handle("codex", hookJson(OTHER, other, "startup")))
        assertNull(SessionOwnership(own).newestFor(elsewhere), "a hook without a cwd enters nothing")
    }

    private fun head(own: Path, served: List<String>): LaunchHead = LaunchHead(
        head = runningHead("codex"),
        auth = absentAuth("test"),
        spec = LaunchSpec(
            trees = HeadTrees(own),
            pinnedModel = PINNED,
            availableModelIds = served,
            modelLabels = served.associateWith { it },
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
