// NEW: E2E proof for V4-64 (shared Claude Code transcripts across heads, v0.4.0 resume). The
// operator story: run out of credits on one account, switch head, `--resume` the same session.
// Claude Code finds a session by listing $CLAUDE_CONFIG_DIR/projects/<encoded-cwd>/<id>.jsonl, so
// the contract is filesystem IDENTITY: head B's spelling of the transcript must resolve to the SAME
// real file head A wrote, not merely to a file with the same name. Two heads are launched through
// the REAL entry ControlPlane wires — one LaunchService over one ClaudeConfigMaterializer(home) —
// against one temp global ~/.claude. (LaunchSpecFactory needs the daemon's Topology/SignInPlanner
// wiring, so the LaunchSpec is built here the way LaunchServiceTest builds it.)
//
// The LINK is asserted BEFORE any transcript is written, on purpose: a policy that stops sharing
// projects must red on "projects is not a symlink" — the contract — and never on a missing file
// further down, which would be the same red for a dozen unrelated causes (mutation duty, V4-65).
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.core.compaction.SessionProject
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import java.nio.file.Files
import java.nio.file.Path

class SharedTranscriptsResumeTest {

    // config/splice.example.toml's share list plus `projects` — the operator's real policy once
    // V4-64 lands. The mutation (drop "projects") is what must turn test 1 red on the LINK.
    private val sharing = ClaudePolicy(
        share = setOf(
            "settings", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md", "mcps",
            "sessions", "projects",
        ),
        isolate = emptySet(),
    )
    private val sessionId = "0f6b1c2e-7d3a-4b8e-9c1d-2a5f6e7b8c9d"

    @Test
    fun `a transcript written through head A is the same real file on head B and resolves to its cwd`(
        @TempDir home: Path,
    ) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val headA = launch(service, home, "a", sharing)
        val headB = launch(service, home, "b", sharing)
        val global = home.resolve(".claude/projects")
        // THE CONTRACT — asserted first (see header): both heads' projects/ ARE the global dir.
        assertLinkedToGlobal(headA, global)
        assertLinkedToGlobal(headB, global)

        val cwd = home.resolve("work/repo")
        val onA = headA.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        Files.createDirectories(onA.parent)
        Files.writeString(onA, transcript(cwd))

        val onB = headB.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        assertTrue(Files.isRegularFile(onB), "head B must see the transcript head A wrote at $onB")
        assertEquals(onA.toRealPath(), onB.toRealPath(), "--resume on head B needs the SAME file, not a copy")
        assertArrayEquals(Files.readAllBytes(onA), Files.readAllBytes(onB))
        // compaction follows the session too: no registry entry, resolved from the global transcript
        val project = SessionProject(home.resolve(".claude/sessions"), global).projectFor(sessionId)
        assertEquals(cwd.toAbsolutePath().normalize(), project)
    }

    // The migration case, which is EVERY head on the operator machine: projects/ is already a real
    // directory holding thousands of transcripts. Adding `projects` to the share list alone would
    // silently do nothing (linkOneShared never replaces a real dir), so V4-64 migrates the contents
    // — per encoded-cwd subdir, <id>.jsonl files and <id>/ subagent dirs alike — and links.
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
        val mainBytes = transcript(cwd).toByteArray()
        val subagentBytes = """{"type":"user","sessionId":"$sessionId","cwd":"$cwd","isSidechain":true}""".toByteArray()
        Files.write(headProject.resolve("$sessionId.jsonl"), mainBytes)
        Files.write(headProject.resolve(sessionId).resolve("subagent.jsonl"), subagentBytes)

        launch(service, home, "a", sharing)

        val global = home.resolve(".claude/projects")
        assertLinkedToGlobal(headA, global)
        val globalProject = global.resolve(encodedCwd(cwd))
        assertArrayEquals(mainBytes, Files.readAllBytes(globalProject.resolve("$sessionId.jsonl")))
        assertArrayEquals(subagentBytes, Files.readAllBytes(globalProject.resolve(sessionId).resolve("subagent.jsonl")))

        // and head B, launched after, sees them by real path — the resume on the other account
        val headB = launch(service, home, "b", sharing)
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

    private fun assertLinkedToGlobal(configDir: Path, global: Path) {
        val projects = configDir.resolve("projects")
        assertTrue(Files.isSymbolicLink(projects), "$projects must be a symlink to the global projects dir")
        assertEquals(global.toRealPath(), projects.toRealPath(), "$projects must resolve to the global projects dir")
    }

    /** One head, launched the way the daemon launches it: the REAL LaunchService materialize. */
    private fun launch(service: LaunchService, home: Path, head: String, policy: ClaudePolicy): Path {
        val configDir = home.resolve(".claude-$head")
        service.launch(spec(configDir, head, policy), emptyList(), dangerouslySkipPermissions = false)
        return configDir
    }

    private fun spec(configDir: Path, head: String, policy: ClaudePolicy) = LaunchSpec(
        configDir = configDir,
        pinnedModel = "gpt-5.6-sol",
        availableModelIds = listOf("gpt-5.6-sol"),
        modelLabels = mapOf("gpt-5.6-sol" to "gpt-5.6-sol"),
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
        Files.createDirectories(global.resolve("projects"))
        Files.createDirectories(global.resolve("sessions"))
        Files.writeString(global.resolve("settings.json"), """{"theme":"dark"}""")
        Files.writeString(home.resolve(".claude.json"), """{"theme":"dark"}""")
    }

    /** Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run
     *  replaced by `-` (`/home/me/repo` -> `-home-me-repo`). */
    private fun encodedCwd(cwd: Path): String = cwd.toAbsolutePath().toString().replace(Regex("[^A-Za-z0-9]"), "-")

    /** Two transcript rows carrying the cwd exactly as SessionProject reads them back. */
    private fun transcript(cwd: Path): String =
        """{"type":"user","sessionId":"$sessionId","cwd":"$cwd","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","cwd":"$cwd","message":{"role":"assistant","content":[]}}
"""
}
