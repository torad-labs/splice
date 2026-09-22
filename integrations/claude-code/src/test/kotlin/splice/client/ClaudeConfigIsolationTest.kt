// NEW: V4-115 head isolation (2026-09-17), re-drawn by V4-168 (2026-09-19) — the wall for the
// operator ruling that head CONFIGURATIONS and details must NEVER leak into other heads, their
// wrappers, or the core claude binary sessions, with exactly TWO dispositioned escapes.
//
// V4-115 drew this wall with one escape (the session registry) and un-linked every head's projects
// tree to a real private one. Two days later the operator named that a regression: "we implemented
// a way for any head to join any session from other heads, that's now gone" — V4-64 had built the
// join on the operator's own words, and the operator's splice.toml still said share = [..., "projects"]
// while the code read the spelling as inert. So the ruling covers configuration; transcripts are
// shared session state, and whether a head shares them is the operator's policy (ProjectsLink).
//
// Two assertions, both about GENERATED state (the operator's own shared items — agents, skills,
// hooks, CLAUDE.md — are shared by design and are not this wall's subject):
//   (a) every symlink under the materialized head dir stays INSIDE that head dir or names one of the
//       two escapes, and every regular file under it has nlink 1 (a hardlink is a second name for
//       the same bytes, so its content is reachable from wherever the other name lives);
//   (b) materialization creates nothing under the fake vanilla dir outside the escapes the POLICY
//       opened, and modifies nothing that was already there.
//
// THE TWO DISPOSITIONED ESCAPES, asserted explicitly and by name below:
//   sessions — <home>/.claude/sessions, the live-session registry SessionRegistryLink points at:
//              Claude Code's peer registry, machine-global by construction (the message sockets live
//              in $XDG_RUNTIME_DIR/cc-socks); cross-session messaging and the daemon's pid-to-head
//              attribution depend on every head seeing the same one. No model id, no conversation.
//   projects — <home>/.claude/projects, the transcript tree ProjectsLink merges into and links at,
//              ONLY when the head's policy shares projects: it is what makes a session started on
//              one head resumable from every other. A head that does not share projects keeps a
//              real private tree, and for that head the vanilla projects path is a hole by name.
// Any OTHER path under the vanilla tree is a hole, and both assertions fail on it by name.
package splice.client

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
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isSymbolicLink

/** The two dispositioned escapes, named ONCE so the assertions and their messages say the same words. */
private const val REGISTRY_DIR = "sessions"
private const val TRANSCRIPTS_DIR = "projects"

private const val TRANSCRIPT = """{"type":"user","sessionId":"abc","cwd":"/work/repo","message":{"role":"user","content":"hi"}}
"""

class ClaudeConfigIsolationTest {

    // Claude Code's projects/ subdir name: the absolute cwd with every non-alphanumeric run
    // replaced by `-` (`/home/me/repo` -> `-home-me-repo`).
    private fun encodedCwd(cwd: Path): String =
        cwd.toAbsolutePath().toString().replace(Regex("[^A-Za-z0-9]"), "-")

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    private fun vanilla(home: Path): Path = home.resolve(".claude")

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

    /** (a) Nothing under [head] may resolve outside it except the named escapes. */
    private fun assertHeadPrivate(head: Path, escapes: Set<Path>) {
        val realHead = head.toRealPath()
        Files.walk(head).use { stream ->
            stream.forEach { entry -> assertEntryPrivate(entry, realHead, escapes) }
        }
    }

    private fun assertEntryPrivate(entry: Path, realHead: Path, escapes: Set<Path>) {
        if (Files.isSymbolicLink(entry)) {
            val target = Files.readSymbolicLink(entry)
            val resolved = (if (target.isAbsolute) target else entry.parent.resolve(target)).normalize()
            assertTrue(
                resolved.startsWith(realHead) || resolved in escapes,
                "$entry points at $resolved, outside the head dir — the only allowed escapes are $escapes",
            )
        } else if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
            assertEquals(
                1,
                Files.getAttribute(entry, "unix:nlink") as Int,
                "$entry has more than one name (hardlink) — its bytes are reachable outside the head dir",
            )
        }
    }

    /** (b) Every difference under the vanilla dir must be one of [opened] escapes, whose names
     *  RELATIVE TO the vanilla dir are the snapshot's own frame. */
    private fun assertVanillaUntouched(before: Map<String, String>, after: Map<String, String>, opened: Set<String>) {
        (after.keys - before.keys).forEach { added ->
            assertTrue(
                opened.any { added == it || added.startsWith("$it/") },
                "$added appeared under the vanilla config dir — materialize created it, and only $opened " +
                    "may be created there under this policy",
            )
        }
        (after.keys intersect before.keys).forEach { kept ->
            assertEquals(before[kept], after[kept], "$kept under the vanilla config dir was modified by materialize")
        }
    }

    @Test
    fun `a head that does not share projects keeps every path inside itself and touches only the registry in vanilla`(
        @TempDir home: Path,
    ) {
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val head = home.resolve(".claude-codex")
        val transcript = write(head.resolve(TRANSCRIPTS_DIR).resolve(encodedCwd(cwd)).resolve("abc.jsonl"), TRANSCRIPT)
        Files.createDirectories(vanilla(home))
        val before = snapshot(vanilla(home))

        materialize(home, head, setOf(REGISTRY_DIR))

        assertHeadPrivate(head, setOf(vanilla(home).resolve(REGISTRY_DIR)))
        assertVanillaUntouched(before, snapshot(vanilla(home)), setOf(REGISTRY_DIR))
        assertFalse(
            Files.exists(vanilla(home).resolve(TRANSCRIPTS_DIR), NOFOLLOW_LINKS),
            "the vanilla projects tree must not exist: this head's policy keeps its transcripts private",
        )
        assertFalse(head.resolve(TRANSCRIPTS_DIR).isSymbolicLink(), "the head projects dir must be REAL, not a link")
        assertEquals(TRANSCRIPT, Files.readString(transcript), "the head's own transcript survives byte-identical")
    }

    // V4-168, the join itself: a head whose policy shares projects has its real tree merged into the
    // vanilla one — SAME inode, so a live writer keeps appending — and its projects path becomes the
    // link. Everything else under the head stays private and everything else under vanilla stays as
    // it was. Mutant: drop projects from sharedLinkItems, or make ProjectsLink un-link again — the
    // symlink assertion and the same-file assertion both go red by name.
    @Test
    fun `a head that shares projects joins the vanilla transcript tree and leaks nothing else`(@TempDir home: Path) {
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val head = home.resolve(".claude-codex")
        val mine = write(head.resolve(TRANSCRIPTS_DIR).resolve(encodedCwd(cwd)).resolve("abc.jsonl"), TRANSCRIPT)
        val foreign = write(
            vanilla(home).resolve(TRANSCRIPTS_DIR).resolve(encodedCwd(cwd)).resolve("other-head.jsonl"),
            """{"type":"assistant","sessionId":"other-head","message":{"model":"gpt-6-astra"}}
""",
        )
        write(vanilla(home).resolve("settings.json"), """{"theme":"dark"}""")
        // The inode BEFORE the swap: afterwards `mine` resolves through the link, so only the key
        // captured now can prove the merge was a hardlink and not a copy.
        val inode = Files.readAttributes(mine, BasicFileAttributes::class.java).fileKey()
        val before = snapshot(vanilla(home))

        materialize(home, head, setOf(REGISTRY_DIR, TRANSCRIPTS_DIR))

        val escapes = setOf(vanilla(home).resolve(REGISTRY_DIR), vanilla(home).resolve(TRANSCRIPTS_DIR))
        assertTrue(head.resolve(TRANSCRIPTS_DIR).isSymbolicLink(), "the head's projects dir must be the link")
        assertEquals(vanilla(home).resolve(TRANSCRIPTS_DIR), Files.readSymbolicLink(head.resolve(TRANSCRIPTS_DIR)))
        assertHeadPrivate(head, escapes)
        assertVanillaUntouched(before, snapshot(vanilla(home)), setOf(REGISTRY_DIR, TRANSCRIPTS_DIR))
        // The merge: the head's transcript is reachable at the vanilla name, and it is the SAME file.
        val merged = vanilla(home).resolve(TRANSCRIPTS_DIR).resolve(encodedCwd(cwd)).resolve("abc.jsonl")
        assertTrue(Files.isRegularFile(merged, NOFOLLOW_LINKS), "the head's transcript now lives in the shared tree")
        assertEquals(
            inode,
            Files.readAttributes(merged, BasicFileAttributes::class.java).fileKey(),
            "merged by hardlink — the same inode — so a live append lands in both names",
        )
        assertEquals(TRANSCRIPT, Files.readString(merged))
        // ...and the foreign transcript already there is neither moved, copied nor rewritten.
        assertEquals(1, Files.getAttribute(foreign, "unix:nlink") as Int, "the foreign transcript gains no second name")
        assertTrue(Files.isRegularFile(foreign), "the foreign transcript must not be deleted or moved")
        assertTrue(
            Files.isRegularFile(head.resolve(TRANSCRIPTS_DIR).resolve(encodedCwd(cwd)).resolve("other-head.jsonl")),
            "and THROUGH the link this head now lists the other head's session — the join",
        )
    }

    @Test
    fun `a head already linked to the vanilla tree stays linked, vanilla content untouched`(@TempDir home: Path) {
        val cwd = home.resolve("work/repo").toAbsolutePath()
        val vanillaProjects = Files.createDirectories(vanilla(home).resolve(TRANSCRIPTS_DIR))
        val foreign = write(
            vanillaProjects.resolve(encodedCwd(cwd)).resolve("other-head.jsonl"),
            """{"type":"assistant","sessionId":"other-head","message":{"model":"gpt-6-astra"}}
""",
        )
        val head = Files.createDirectories(home.resolve(".claude-codex"))
        Files.createSymbolicLink(head.resolve(TRANSCRIPTS_DIR), vanillaProjects)
        val before = snapshot(vanilla(home))

        materialize(home, head, setOf(REGISTRY_DIR, TRANSCRIPTS_DIR))

        assertTrue(head.resolve(TRANSCRIPTS_DIR).isSymbolicLink(), "the link is the desired state and stays")
        assertEquals(vanillaProjects, Files.readSymbolicLink(head.resolve(TRANSCRIPTS_DIR)))
        assertVanillaUntouched(before, snapshot(vanilla(home)), setOf(REGISTRY_DIR))
        assertEquals(1, Files.getAttribute(foreign, "unix:nlink") as Int, "no second name for the vanilla transcript")
        assertTrue(Files.isRegularFile(foreign), "the vanilla transcript must not be deleted or moved")
    }

    @Test
    fun `the operator share vocabulary links projects and still delivers the operator's shared items`(
        @TempDir home: Path,
    ) {
        // The example TOML's own share list, friendly spellings and all — the policy a head really
        // materializes with, and the one the operator's own splice.toml carries.
        val head = home.resolve(".claude-codex")
        val shared = write(vanilla(home).resolve("CLAUDE.md"), "# global rules")
        Files.createDirectories(vanilla(home).resolve("agents"))
        val before = snapshot(vanilla(home))

        val share = setOf(
            "settings", "agents", "commands", "skills", "hooks",
            "plugins", "claude_md", "mcps", REGISTRY_DIR, TRANSCRIPTS_DIR,
        )
        materialize(home, head, share)

        assertTrue(head.resolve("CLAUDE.md").isSymbolicLink(), "the operator's shared CLAUDE.md must still arrive")
        assertEquals(shared.toRealPath(), head.resolve("CLAUDE.md").toRealPath())
        assertTrue(head.resolve(TRANSCRIPTS_DIR).isSymbolicLink(), "projects in the share list means the link")
        assertTrue(
            Files.isDirectory(vanilla(home).resolve(TRANSCRIPTS_DIR), NOFOLLOW_LINKS),
            "the vanilla projects tree is created when absent: a fresh machine is where the join is wanted most",
        )
        assertVanillaUntouched(before, snapshot(vanilla(home)), setOf(REGISTRY_DIR, TRANSCRIPTS_DIR))
    }
}
