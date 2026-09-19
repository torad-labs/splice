// NEW: V4-115 cross-head resume, end to end through the REAL launch entry (2026-09-17). The
// operator story is unchanged — run out of credits on one account, switch head, resume the same
// session — but the mechanism is. V4-64 bought it by making every head's projects/ one symlink into
// the operator's vanilla ~/.claude/projects; measured 2026-09-17, 95 transcripts carrying head model
// ids sat in the vanilla tree and the vanilla client printed "Session model deepseek-flash could not
// be restored" on every restore. OPERATOR RULING: head configurations and details must NEVER leak
// into other heads, their wrappers, or the core claude binary sessions.
//
// So the three properties pinned here, all through LaunchService over one ClaudeConfigMaterializer
// (the object ControlPlane wires):
//   BOUNDED     a foreign session is INVISIBLE to this head's picker — its projects tree holds it
//               only after an explicit `-r SESSION_ID` adoption, and a plain launch never copies;
//   ON DEMAND   `-r SESSION_ID` finds it in the other head's tree and COPIES it in (no link, source
//               byte-identical), and a `-r` naming an id no head holds copies nothing;
//   MODEL       the copy's assistant rows name THIS head's pinned model, which is what keeps Claude
//               Code's resume restore from refusing the session.
//
// LaunchSpecFactory needs the daemon's Topology/SignInPlanner/HeadBuildInputs wiring, so the
// LaunchSpec is built here the way LaunchServiceTest builds it — with HeadTrees spelled
// explicitly, exactly as LaunchSpecFactory derives them from the topology.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.HeadTrees
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

class SharedTranscriptsResumeTest {

    /** config/splice.example.toml's own share list. `projects` is NOT in it any more (V4-115) and a
     *  policy that names it must change nothing — that is the second test's subject. */
    private val sharing = ClaudePolicy(
        share = setOf(
            "settings", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md", "mcps", "sessions",
        ),
        isolate = emptySet(),
    )

    private val sessionId = "0f6b1c2e-7d3a-4b8e-9c1d-2a5f6e7b8c9d"
    private val headModel = "gpt-5.6-sol"

    @Test
    fun `a foreign session is invisible to the picker until -r copies it in, models and all`(@TempDir home: Path) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val cwd = home.resolve("work/repo")
        val headA = launch(service, home, "a", sharing, siblings = emptyList())
        val onA = headA.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        Files.createDirectories(onA.parent)
        Files.writeString(onA, transcript(cwd, model = "deepseek-flash"))
        val sourceBytes = Files.readString(onA)

        val headB = launch(service, home, "b", sharing, siblings = listOf(headA))
        val onB = headB.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        assertFalse(Files.exists(onB, NOFOLLOW_LINKS), "a plain launch must not pull another head's session in")
        assertTrue(Files.isDirectory(headB.resolve("projects"), NOFOLLOW_LINKS), "each head owns a REAL projects tree")
        assertFalse(
            Files.isSymbolicLink(headB.resolve("projects")),
            "the vanilla-link design is gone: a head's projects dir is never a symlink",
        )

        service.launch(
            spec(headB, "b", sharing, listOf(headA)),
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
        val headA = launch(service, home, "a", sharing, siblings = emptyList())

        val recipe = service.launch(
            spec(headA, "a", sharing, emptyList()),
            listOf("-r", "11111111-2222-3333-4444-555555555555"),
            dangerouslySkipPermissions = false,
        )

        assertEquals(
            emptyList<String>(),
            Files.list(headA.resolve("projects")).use { it.toList() }.map { it.fileName.toString() },
            "the head's tree stays empty: nothing is invented for an id that exists nowhere",
        )
        assertFalse(
            Files.exists(home.resolve(".claude/projects"), NOFOLLOW_LINKS),
            "the vanilla tree gains no projects dir",
        )
        assertTrue(
            recipe.warning.orEmpty().contains("is in no transcript tree"),
            "the refusal is said out loud, not left to the client: ${recipe.warning}",
        )
    }

    @Test
    fun `the -r picker is head-bounded and a policy naming projects changes nothing`(@TempDir home: Path) {
        seedGlobal(home)
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val cwd = home.resolve("work/repo")
        val namingProjects = ClaudePolicy(share = sharing.share + "projects", isolate = emptySet())
        val headA = launch(service, home, "a", namingProjects, siblings = emptyList())
        val mine = headA.resolve("projects").resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")
        Files.createDirectories(mine.parent)
        Files.writeString(mine, transcript(cwd, "x"))

        // `-r` with NO id is the picker: it opens THIS head's tree and must touch nothing else.
        service.launch(spec(headA, "a", namingProjects, emptyList()), listOf("-r"), dangerouslySkipPermissions = false)
        service.launch(spec(headA, "a", namingProjects, emptyList()), listOf("-c"), dangerouslySkipPermissions = false)

        val headProjects = headA.resolve("projects")
        assertFalse(headProjects.isSymbolicLink(), "a policy naming projects must not resurrect the link")
        assertTrue(Files.isRegularFile(headProjects.resolve(encodedCwd(cwd)).resolve("$sessionId.jsonl")))
        assertFalse(
            Files.exists(home.resolve(".claude/projects"), NOFOLLOW_LINKS),
            "the vanilla projects tree is never created, under any policy",
        )
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
