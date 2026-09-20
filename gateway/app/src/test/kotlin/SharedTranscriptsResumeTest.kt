// NEW: cross-head resume, end to end through the REAL launch entry. The operator story: run out of
// credits on one account, switch head, resume the same session. Claude Code finds a session by
// listing $CLAUDE_CONFIG_DIR/projects/<encoded-cwd>/<id>.jsonl, so the join is filesystem IDENTITY:
// head B's spelling of the transcript must resolve to the SAME real file head A wrote.
//
// V4-64/V4-65 (2026-09-16) built and proved that through one shared tree. V4-115 (2026-09-17) read
// the config-isolation ruling as covering transcripts, gave every head a private tree, kept only an
// explicit `-r SESSION_ID` copy, and rewrote this file around it. V4-168 (2026-09-19) puts the join
// back after the operator named its removal a regression — and keeps V4-115's copy path for the
// one policy that still wants it. So this file pins BOTH contracts, each under the policy that
// selects it, all through LaunchService over one ClaudeConfigMaterializer (the object ControlPlane
// wires):
//   SHARED    (share names projects — the operator's own policy) two heads' projects/ ARE the global
//             tree, a transcript written through A is the same file on B, a head whose projects/ was
//             a real dir has it migrated in and linked, and SessionProject resolves the cwd from it;
//   ISOLATED  (isolate names projects) a foreign session is INVISIBLE to this head's picker until an
//             explicit `-r SESSION_ID` COPIES it in with the assistant rows rewritten to THIS head's
//             model, and a `-r` naming an id no head holds copies nothing.
//
// The LINK is asserted BEFORE any transcript is written, on purpose: a change that stops sharing
// projects must red on "projects is not a symlink" — the contract — and never on a missing file
// further down, which would be the same red for a dozen unrelated causes (mutation duty, V4-65).
//
// LaunchSpecFactory needs the daemon's Topology/SignInPlanner/HeadBuildInputs wiring, so the
// LaunchSpec is built here the way LaunchServiceTest builds it — with HeadTrees spelled explicitly,
// exactly as LaunchSpecFactory derives them from the topology.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.HeadTrees
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.core.compaction.SessionProject
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

class SharedTranscriptsResumeTest {

    /** config/splice.example.toml's share list, `projects` included — the operator's real policy
     *  (their splice.toml carries the same list). The mutation (drop "projects") must turn the
     *  shared cells red on the LINK. */
    private val sharing = ClaudePolicy(
        share = setOf(
            "settings", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md", "mcps",
            "sessions", "projects",
        ),
        isolate = emptySet(),
    )

    /** The same list with projects ISOLATED: the one policy under which a head keeps a private tree. */
    private val isolating = ClaudePolicy(share = sharing.share, isolate = setOf("projects"))

    private val sessionId = "0f6b1c2e-7d3a-4b8e-9c1d-2a5f6e7b8c9d"
    private val headModel = "gpt-5.6-sol"

    // ───────────────────────────── SHARED: the join ─────────────────────────────

    @Test
    fun `a transcript written through head A is the same real file on head B and resolves to its cwd`(
        @TempDir home: Path,
    ) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val headA = launch(service, home, "a", sharing, siblings = emptyList())
        val headB = launch(service, home, "b", sharing, siblings = listOf(headA))
        val global = home.resolve(".claude/projects")
        // THE CONTRACT — asserted first (see header): both heads' projects/ ARE the global dir.
        assertLinkedToGlobal(headA, global)
        assertLinkedToGlobal(headB, global)

        val cwd = home.resolve("work/repo")
        val onA = headA.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        Files.createDirectories(onA.parent)
        Files.writeString(onA, transcript(cwd, model = "deepseek-flash"))

        val onB = headB.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        assertTrue(Files.isRegularFile(onB), "head B must see the transcript head A wrote at $onB")
        assertEquals(onA.toRealPath(), onB.toRealPath(), "--resume on head B needs the SAME file, not a copy")
        assertArrayEquals(Files.readAllBytes(onA), Files.readAllBytes(onB))
        // compaction follows the session too: no registry entry, resolved from the global transcript
        val project = SessionProject(home.resolve(".claude/sessions"), global).projectFor(sessionId)
        assertEquals(cwd.toAbsolutePath().normalize(), project)
        // `-r SESSION_ID` on head B finds it in its own (shared) tree: nothing is copied, and (V4-169)
        // the assistant rows are moved onto head B's model WHERE THEY LIE, so Claude Code's restore
        // check finds a model this head serves. Same file, same inode, one row changed.
        service.launch(
            spec(headB, "b", sharing, listOf(headA)),
            listOf("-r", sessionId),
            dangerouslySkipPermissions = false,
        )
        assertEquals(headModel, modelOf(Files.readString(onB)), "moved onto the resuming head's model in place")
        assertEquals(onA.toRealPath(), onB.toRealPath(), "still the one shared file")
    }

    // The migration case, which is EVERY head on the operator machine after V4-115: projects/ is a
    // real directory holding transcripts. Naming `projects` in the share list alone would silently
    // do nothing (linkOneShared never replaces a real dir), so ProjectsLink migrates the contents —
    // per encoded-cwd subdir, <id>.jsonl files and <id>/ subagent dirs alike — and links.
    @Test
    fun `a head whose projects is a real dir has its transcripts migrated into the global and linked`(
        @TempDir home: Path,
    ) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val cwd = home.resolve("work/repo")
        val headA = home.resolve(".claude-a")
        val headProject = headA.resolve("projects").resolve(encodedCwd(cwd))
        Files.createDirectories(headProject.resolve(sessionId))
        val mainBytes = transcript(cwd, model = headModel).toByteArray()
        val subagentBytes = """{"type":"user","sessionId":"$sessionId","cwd":"$cwd","isSidechain":true}""".toByteArray()
        Files.write(headProject.resolve("$sessionId.jsonl"), mainBytes)
        Files.write(headProject.resolve(sessionId).resolve("subagent.jsonl"), subagentBytes)

        launch(service, home, "a", sharing, siblings = emptyList())

        val global = home.resolve(".claude/projects")
        assertLinkedToGlobal(headA, global)
        val globalProject = global.resolve(encodedCwd(cwd))
        assertArrayEquals(mainBytes, Files.readAllBytes(globalProject.resolve("$sessionId.jsonl")))
        assertArrayEquals(subagentBytes, Files.readAllBytes(globalProject.resolve(sessionId).resolve("subagent.jsonl")))

        // and head B, launched after, sees them by real path — the resume on the other account
        val headB = launch(service, home, "b", sharing, siblings = listOf(headA))
        val onB = headB.resolve("projects").resolve(encodedCwd(cwd))
        assertEquals(
            globalProject.resolve("$sessionId.jsonl").toRealPath(),
            onB.resolve("$sessionId.jsonl").toRealPath(),
        )
        assertEquals(
            globalProject.resolve(sessionId).resolve("subagent.jsonl").toRealPath(),
            onB.resolve(sessionId).resolve("subagent.jsonl").toRealPath(),
        )
        // head A's own spelling still reaches the migrated file: a live session on A keeps working
        assertEquals(
            globalProject.resolve("$sessionId.jsonl").toRealPath(),
            headProject.resolve("$sessionId.jsonl").toRealPath(),
        )
    }

    // ───────────────────────────── ISOLATED: the copy ─────────────────────────────

    @Test
    fun `under an isolating policy a foreign session is invisible until -r copies it in, models and all`(
        @TempDir home: Path,
    ) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val cwd = home.resolve("work/repo")
        val headA = launch(service, home, "a", isolating, siblings = emptyList())
        val onA = headA.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        Files.createDirectories(onA.parent)
        Files.writeString(onA, transcript(cwd, model = "deepseek-flash"))
        val sourceBytes = Files.readString(onA)

        val headB = launch(service, home, "b", isolating, siblings = listOf(headA))
        val onB = headB.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        assertFalse(Files.exists(onB, NOFOLLOW_LINKS), "a plain launch must not pull another head's session in")
        assertTrue(Files.isDirectory(headB.resolve("projects"), NOFOLLOW_LINKS), "an isolating head owns a REAL tree")
        assertFalse(headB.resolve("projects").isSymbolicLink(), "isolate wins over share: no link")

        service.launch(
            spec(headB, "b", isolating, listOf(headA)),
            listOf("-r", sessionId),
            dangerouslySkipPermissions = false,
        )

        assertTrue(Files.isRegularFile(onB, NOFOLLOW_LINKS), "the adoption puts a real transcript in this head's tree")
        assertEquals(sourceBytes, Files.readString(onA), "the source transcript is byte-identical after the copy")
        assertEquals(
            headModel,
            modelOf(Files.readString(onB)),
            "the copy's assistant model follows the RESUMING head, so the restore cannot be refused",
        )
        // ...and the two heads hold DIFFERENT files now: no shared inode, no shared append.
        assertFalse(Files.isSameFile(onA, onB), "the adoption is a copy, never a link or a rename")
    }

    @Test
    fun `a resume that names an id no head holds copies nothing`(@TempDir home: Path) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val headA = launch(service, home, "a", isolating, siblings = emptyList())

        val recipe = service.launch(
            spec(headA, "a", isolating, emptyList()),
            listOf("-r", "11111111-2222-3333-4444-555555555555"),
            dangerouslySkipPermissions = false,
        )

        assertEquals(
            emptyList<String>(),
            Files.list(headA.resolve("projects")).use { it.toList() }.map { it.fileName.toString() },
            "the head's tree stays empty: nothing is invented for an id that exists nowhere",
        )
        assertTrue(
            recipe.warning.orEmpty().contains("is in no transcript tree"),
            "the refusal is said out loud, not left to the client: ${recipe.warning}",
        )
    }

    private fun assertLinkedToGlobal(configDir: Path, global: Path) {
        val projects = configDir.resolve("projects")
        assertTrue(Files.isSymbolicLink(projects), "$projects must be a symlink to the global projects dir")
        assertEquals(global.toRealPath(), projects.toRealPath(), "$projects must resolve to the global projects dir")
    }

    /** One head, launched the way the daemon launches it: the REAL LaunchService materialize. */
    private fun launch(
        service: LaunchService,
        home: Path,
        head: String,
        policy: ClaudePolicy,
        siblings: List<Path>,
    ): Path {
        val configDir = home.resolve(".claude-$head")
        service.launch(spec(configDir, head, policy, siblings), emptyList(), dangerouslySkipPermissions = false)
        return configDir
    }

    private fun spec(configDir: Path, head: String, policy: ClaudePolicy, siblings: List<Path>) = LaunchSpec(
        trees = HeadTrees(configDir, siblings),
        pinnedModel = headModel,
        availableModelIds = listOf(headModel),
        modelLabels = mapOf(headModel to headModel),
        contextWindow = 272_000,
        apiTimeoutMs = 960_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "curl -sS --data-binary @- http://127.0.0.1:3096/statusline/$head",
        loginCommand = "claude-$head login",
        signInLabel = "Head $head",
        headKey = head,
        policy = policy,
        port = 3099,
        inferenceToken = "test-inference-token",
    )

    /** The operator's global ~/.claude as a machine that has run plain `claude` leaves it. */
    private fun seedGlobal(home: Path) {
        val global = home.resolve(".claude")
        Files.createDirectories(global.resolve("sessions"))
        Files.writeString(global.resolve("settings.json"), """{"theme":"dark"}""")
        Files.writeString(home.resolve(".claude.json"), """{"theme":"dark"}""")
    }

    /** Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run
     *  replaced by `-` (`/home/me/repo` -> `-home-me-repo`). */
    private fun encodedCwd(cwd: Path): String = cwd.toAbsolutePath().toString().replace(Regex("[^A-Za-z0-9]"), "-")

    /** Two transcript rows carrying the cwd exactly as SessionProject reads them back. */
    private fun transcript(cwd: Path, model: String): String =
        """{"type":"user","sessionId":"$sessionId","cwd":"$cwd","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","cwd":"$cwd","message":{"model":"$model","content":[]}}
"""

    private fun modelOf(text: String): String? =
        JsonScalars.str(
            Json.parseToJsonElement(text.split("\n")[1]).jsonObject["message"] as? JsonObject,
            "model",
        )
}
