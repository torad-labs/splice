// NEW: V4-115 head isolation (2026-09-17) — the wall for the operator ruling that head
// configurations and details must NEVER leak into other heads, their wrappers, or the core claude
// binary sessions. Commit 91d68f3e pointed every head's CLAUDE_CONFIG_DIR/projects at the operator's
// vanilla ~/.claude/projects so a session could resume on another head; measured 2026-09-17, 95
// transcripts carrying head model ids sat in the vanilla tree and the vanilla client printed
// "Session model deepseek-flash could not be restored" on every restore.
//
// Two assertions, both about GENERATED state (the operator's own shared items — agents, skills,
// hooks, CLAUDE.md — are shared by design and are not this wall's subject):
//   (a) every symlink under the materialized head dir stays INSIDE that head dir, and every regular
//       file under it has nlink 1 (a hardlink is a second name for the same bytes, so its content is
//       reachable from wherever the other name lives);
//   (b) materialization creates nothing under the fake vanilla dir outside the ONE dispositioned
//       escape, and modifies nothing that was already there.
//
// THE ONE DISPOSITIONED ESCAPE, asserted explicitly and by name below: <home>/.claude/sessions, the
// live-session registry SessionRegistryLink points at. It is Claude Code's peer registry — a
// per-pid registration json plus a key file, machine-global by construction (the message sockets
// live in $XDG_RUNTIME_DIR/cc-socks), and cross-session messaging plus the daemon's pid-to-head
// attribution (ControlPlane's SessionRegistry) depend on every head seeing the same one. It is not
// head configuration and it is not a transcript: it carries no model id and no conversation. Any
// OTHER path under the vanilla tree — projects/ above all — is a hole, and both assertions fail on
// it by name.
package splice.core.launch

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/** The one dispositioned escape, named ONCE so both assertions and their messages say the same word. */
private const val REGISTRY_DIR = "sessions"

class ClaudeConfigIsolationTest {

    // Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run
    // replaced by `-` (`/home/me/repo` -> `-home-me-repo`).
    private fun encodedCwd(cwd: Path): String =
        cwd.toAbsolutePath().toString().replace(Regex("[^A-Za-z0-9]"), "-")

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** The ONE dispositioned escape: the machine-global live-session registry. */
    private fun registryEscape(home: Path): Path = home.resolve(".claude").resolve("sessions")

    private fun materialize(home: Path, configDir: Path, share: Set<String>) {
        Files.createDirectories(configDir)
        // A blank loginCommand so LoginInterception writes no hooks: this wall is about generated
        // state only, and the login wiring has its own tests.
        ClaudeConfigMaterializer(home).materialize(
            MaterializeSpec(
                configDir = configDir,
                policy = ClaudePolicy(share = share, isolate = emptySet()),
                availableModelIds = listOf("head-model"),
                defaultModel = "head-model",
                modelOptionsCache = buildJsonObject { put("cache", "x") },
                statuslineCommand = "curl :3096/statusline",
                headKey = "codex",
            ),
        )
    }

    /** Name -> content for every entry below [root], walked WITHOUT following symlinks: a symlinked
     *  directory is one entry, never a descent. Used as the "was anything here changed" oracle. */
    private fun snapshot(root: Path): Map<String, String> {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return emptyMap()
        return Files.walk(root).use { stream ->
            stream.toList().associate { entry ->
                val name = root.relativize(entry).toString()
                when {
                    Files.isSymbolicLink(entry) -> name to "symlink:" + Files.readSymbolicLink(entry)
                    Files.isDirectory(entry, NOFOLLOW_LINKS) -> name to "dir"
                    else -> name to "file:" + Files.readString(entry)
                }
            }
        }
    }

    /** (a) Nothing under [head] may resolve outside it except the named registry escape. */
    private fun assertHeadPrivate(head: Path, allowedEscape: Path) {
        val realHead = head.toRealPath()
        Files.walk(head).use { stream ->
            stream.forEach { entry -> assertEntryPrivate(entry, realHead, allowedEscape) }
        }
    }

    private fun assertEntryPrivate(entry: Path, realHead: Path, allowedEscape: Path) {
        if (Files.isSymbolicLink(entry)) {
            val target = Files.readSymbolicLink(entry)
            val resolved = (if (target.isAbsolute) target else entry.parent.resolve(target)).normalize()
            assertTrue(
                resolved.startsWith(realHead) || resolved == allowedEscape,
                "$entry points at $resolved, outside the head dir — the ONLY allowed escape is " +
                    "$allowedEscape (Claude Code's machine-global live-session registry)",
            )
        } else if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
            assertEquals(
                1,
                Files.getAttribute(entry, "unix:nlink") as Int,
                "$entry has more than one name (hardlink) — its bytes are reachable outside the head dir",
            )
        }
    }

    /** (b) Every difference under the vanilla dir must be the registry escape, whose name RELATIVE
     *  TO the vanilla dir is exactly [REGISTRY_DIR] — the snapshot's own frame. */
    private fun assertVanillaUntouched(before: Map<String, String>, after: Map<String, String>) {
        (after.keys - before.keys).forEach { added ->
            assertTrue(
                added == REGISTRY_DIR || added.startsWith("$REGISTRY_DIR/"),
                "$added appeared under the vanilla config dir — materialize created it, and only the " +
                    "session registry ($REGISTRY_DIR) may be created there",
            )
        }
        (after.keys intersect before.keys).forEach { kept ->
            assertEquals(before[kept], after[kept], "$kept under the vanilla config dir was modified by materialize")
        }
    }

    @Test
    fun `materialize keeps every head path inside the head dir, and touches only the session registry in vanilla`(
        @TempDir home: Path,
    ) {
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val head = home.resolve(".claude-codex")
        val transcript = write(
            head.resolve("projects").resolve(encodedCwd(cwd)).resolve("abc.jsonl"),
            """{"type":"user","sessionId":"abc","cwd":"$cwd","message":{"role":"user","content":"hi"}}
""",
        )
        Files.createDirectories(home.resolve(".claude"))
        val before = snapshot(home.resolve(".claude"))

        materialize(home, head, setOf("projects", "sessions"))

        assertHeadPrivate(head, registryEscape(home))
        assertVanillaUntouched(before, snapshot(home.resolve(".claude")))
        assertFalse(
            Files.exists(home.resolve(".claude").resolve("projects"), NOFOLLOW_LINKS),
            "the vanilla projects tree must not exist: the head's transcripts are head-private",
        )
        assertFalse(head.resolve("projects").isSymbolicLink(), "the head projects dir must be REAL, not a link")
        assertEquals(
            """{"type":"user","sessionId":"abc","cwd":"$cwd","message":{"role":"user","content":"hi"}}
""",
            Files.readString(transcript),
            "the head's own transcript must survive materialize byte-identical",
        )
    }

    @Test
    fun `a head whose projects dir is the old vanilla link is un-linked to a real dir, vanilla content untouched`(
        @TempDir home: Path,
    ) {
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val vanillaProjects = Files.createDirectories(home.resolve(".claude").resolve("projects"))
        val foreign = write(
            vanillaProjects.resolve(encodedCwd(cwd)).resolve("other-head.jsonl"),
            """{"type":"assistant","sessionId":"other-head","message":{"model":"gpt-6-astra"}}
""",
        )
        val head = Files.createDirectories(home.resolve(".claude-codex"))
        Files.createSymbolicLink(head.resolve("projects"), vanillaProjects)
        val before = snapshot(home.resolve(".claude"))

        materialize(home, head, setOf("projects", "sessions"))

        val headProjects = head.resolve("projects")
        assertFalse(headProjects.isSymbolicLink(), "the vanilla link must be gone")
        assertTrue(Files.isDirectory(headProjects, NOFOLLOW_LINKS), "a real head projects dir must take its place")
        assertEquals(
            emptySet<String>(),
            Files.newDirectoryStream(headProjects).use { it.toList() }.map { it.fileName.toString() }.toSet(),
            "the replacement dir starts EMPTY: nobody's history is moved into this fix",
        )
        assertHeadPrivate(head, registryEscape(home))
        assertVanillaUntouched(before, snapshot(home.resolve(".claude")))
        assertEquals(1, Files.getAttribute(foreign, "unix:nlink") as Int, "no hardlink into the vanilla tree")
        assertTrue(Files.isRegularFile(foreign), "the vanilla transcript must not be deleted or moved")
    }

    @Test
    fun `the operator share vocabulary still leaves a head projects tree real and the vanilla one absent`(
        @TempDir home: Path,
    ) {
        // The example TOML's own share list, friendly spellings and all — the policy a head really
        // materializes with. `projects` is inert there now, but a policy naming it must not resurrect
        // the link, and the shared operator items must still arrive.
        val head = home.resolve(".claude-codex")
        val shared = write(home.resolve(".claude/CLAUDE.md"), "# global rules")
        Files.createDirectories(home.resolve(".claude/agents"))
        val before = snapshot(home.resolve(".claude"))

        val share = setOf(
            "settings", "agents", "commands", "skills", "hooks",
            "plugins", "claude_md", "mcps", "sessions", "projects",
        )
        materialize(home, head, share)

        assertTrue(head.resolve("CLAUDE.md").isSymbolicLink(), "the operator's shared CLAUDE.md must still arrive")
        assertEquals(shared.toRealPath(), head.resolve("CLAUDE.md").toRealPath())
        assertFalse(head.resolve("projects").isSymbolicLink(), "the head projects dir must be REAL under any policy")
        assertFalse(
            Files.exists(home.resolve(".claude").resolve("projects"), NOFOLLOW_LINKS),
            "no policy may cause materialize to create the vanilla projects tree",
        )
        assertVanillaUntouched(before, snapshot(home.resolve(".claude")))
    }
}
