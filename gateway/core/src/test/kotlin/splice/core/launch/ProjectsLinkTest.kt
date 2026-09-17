package splice.core.launch

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.PosixFilePermissions

class ProjectsLinkTest {

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    private fun ino(path: Path): Long = Files.getAttribute(path, "unix:ino") as Long
    private fun nlink(path: Path): Int = Files.getAttribute(path, "unix:nlink") as Int

    /** Name -> content of every regular file below [root], the byte-identity oracle for a tree. */
    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }.toList()
            .associate { root.relativize(it).toString() to Files.readString(it) }
    }

    /** The real fs with one hook: [before] runs ahead of the FIRST move (the aside rename), which is
     *  the moment a live writer can still add to the head tree without the preflight having seen it. */
    private fun fsWith(
        before: (source: Path, target: Path) -> Unit = { _, _ -> },
        link: (link: Path, existing: Path) -> Path = { l, e -> Files.createLink(l, e) },
    ) = object : ProjectsFs {
        override fun move(source: Path, target: Path, vararg options: CopyOption): Path {
            before(source, target)
            return Files.move(source, target, *options)
        }

        override fun createLink(link: Path, existing: Path): Path = link(link, existing)

        override fun createSymbolicLink(link: Path, target: Path): Path = Files.createSymbolicLink(link, target)
    }

    // The materializer end: a share policy naming projects dispatches here. These two live in this
    // file rather than ClaudeConfigMaterializerTest because that class sits at detekt's LargeClass
    // ceiling (400 lines) and two more tests push it over.
    private fun materialize(home: Path, configDir: Path, policy: ClaudePolicy) {
        Files.createDirectories(home.resolve(".claude"))
        val spec = MaterializeSpec(
            configDir = configDir,
            policy = policy,
            availableModelIds = listOf("m"),
            defaultModel = "m",
            modelOptionsCache = buildJsonObject { put("cache", "x") },
            statuslineCommand = "curl :3096/statusline",
            headKey = "codex",
        )
        ClaudeConfigMaterializer(home).materialize(spec)
    }

    @Test
    fun `a share policy naming projects ends with the head transcripts linked to the global dir`(@TempDir home: Path) {
        val headProjects = home.resolve(".claude-codex/projects")
        write(headProjects.resolve("-home-x/abc.jsonl"), "abc") // Claude Code made this before the link existed
        write(home.resolve(".claude/projects/-home-x/abc.jsonl"), "global abc") // and the global one collides

        materialize(home, headProjects.parent, ClaudePolicy(share = setOf("projects"), isolate = emptySet()))

        assertEquals(home.resolve(".claude/projects"), Files.readSymbolicLink(headProjects))
        assertEquals("global abc", Files.readString(home.resolve(".claude/projects/-home-x/abc.jsonl")))
        assertEquals("abc", Files.readString(home.resolve(".claude/projects/-home-x/abc.jsonl.from-codex")))
    }

    @Test
    fun `isolate projects keeps the head transcripts private`(@TempDir home: Path) {
        val headProjects = Files.createDirectories(home.resolve(".claude-codex/projects"))
        Files.createDirectories(home.resolve(".claude/projects"))

        materialize(home, headProjects.parent, ClaudePolicy(share = setOf("projects"), isolate = setOf("projects")))

        assertFalse(Files.isSymbolicLink(headProjects), "isolate wins over share")
        assertTrue(Files.isDirectory(headProjects, NOFOLLOW_LINKS))
    }

    @Test
    fun `a fresh head links to the global projects dir`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("cfg").resolve("projects")
        Files.createDirectories(local.parent)

        ProjectsLink("kimi").link(global, local)

        assertTrue(Files.isSymbolicLink(local))
        assertEquals(global, Files.readSymbolicLink(local))
    }

    // Divergence A: the tree is nested by design (encoded cwd dirs, session subdirs inside them),
    // and a cwd dir both sides hold is merged into, never refused.
    @Test
    fun `a real head tree with cwd dirs and session subdirs migrates and ends as a symlink`(@TempDir tmp: Path) {
        val global = tmp.resolve("global-projects")
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc transcript")
        write(local.resolve("-home-x/abc/subagents/agent-1.jsonl"), "subagent transcript")
        write(local.resolve("-home-x/abc/tool-results/t1.txt"), "tool result")
        write(local.resolve("-home-y/def.jsonl"), "def transcript")
        write(global.resolve("-home-x/other.jsonl"), "already global") // -home-x exists on both sides
        val expected = snapshot(local) + ("-home-x/other.jsonl" to "already global")

        ProjectsLink("kimi").link(global, local)

        assertTrue(Files.isSymbolicLink(local), "the head dir must become the link")
        assertEquals(global, Files.readSymbolicLink(local))
        assertEquals(expected, snapshot(global), "every file present under global, byte-identical, merge kept")
        assertEquals(0, Files.list(tmp).use { s -> s.filter { it.fileName.toString().contains("migrating") }.count() })
        // and through the link the head sees the whole tree it used to own
        assertEquals("abc transcript", Files.readString(local.resolve("-home-x/abc.jsonl")))
    }

    // THE REVIEW FINDING: a transcript is appended to by a live session at all times. The global name
    // must be the SAME inode the writer already holds, so bytes written through the head path before,
    // during and after the swap all land in one file — and once the aside tree is gone it is one name.
    @Test
    fun `a live writer keeps appending to the same inode through the swap`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("projects")
        val transcript = write(local.resolve("-home-x/abc.jsonl"), "line1\n")
        val inodeBefore = ino(transcript)
        val duringSwap = fsWith(before = { source, _ ->
            if (source == local) Files.writeString(transcript, "line2\n", APPEND) // the writer's next message
        })

        ProjectsLink("kimi", duringSwap).link(global, local)
        Files.writeString(local.resolve("-home-x/abc.jsonl"), "line3\n", APPEND) // now resolves via the link

        val migrated = global.resolve("-home-x/abc.jsonl")
        assertEquals(inodeBefore, ino(migrated), "the writer's inode must survive the swap")
        assertEquals("line1\nline2\nline3\n", Files.readString(migrated))
        assertEquals(1, nlink(migrated), "the aside tree is gone, so the inode has one name again")
    }

    // Divergence C: Claude Code writes subagents/agent-<id>.jsonl as an absolute symlink.
    @Test
    fun `a symlink entry migrates with its target string verbatim`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("projects")
        val dest = Path.of("/home/someone/.claude-kimi/projects/-home-x/abc/subagents/agent-1.jsonl")
        Files.createDirectories(local.resolve("-home-x/abc/subagents"))
        Files.createSymbolicLink(local.resolve("-home-x/abc/subagents/agent-1.jsonl"), dest)

        ProjectsLink("kimi").link(global, local)

        val migrated = global.resolve("-home-x/abc/subagents/agent-1.jsonl")
        assertTrue(Files.isSymbolicLink(migrated))
        assertEquals(dest, Files.readSymbolicLink(migrated))
        assertTrue(Files.isSymbolicLink(local), "a symlink entry is expected content, never a refusal")
    }

    // A file the writer creates between preflight and the aside rename never got a link: the sweep
    // after the promotion links it, then the aside tree is deleted.
    @Test
    fun `a straggler created before the aside rename is swept into global`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc")
        val straggling = fsWith(before = { source, _ ->
            if (source == local) write(local.resolve("-home-x/late.jsonl"), "late")
        })

        ProjectsLink("kimi", straggling).link(global, local)

        assertEquals("late", Files.readString(global.resolve("-home-x/late.jsonl")))
        assertEquals(mapOf("-home-x/abc.jsonl" to "abc", "-home-x/late.jsonl" to "late"), snapshot(global))
        assertEquals(0, Files.list(tmp).use { s -> s.filter { it.fileName.toString().contains("migrating") }.count() })
    }

    // Divergence B: the opposite of the sessions rule — a same-named file keeps the GLOBAL copy and
    // parks the head copy beside it under a suffix `--resume` does not list.
    @Test
    fun `a file collision keeps the global copy and parks the head copy as from-headkey`(@TempDir tmp: Path) {
        val global = tmp.resolve("global-projects")
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "head copy")
        write(local.resolve("-home-x/fresh.jsonl"), "only on the head")
        write(global.resolve("-home-x/abc.jsonl"), "global copy")
        val log = mutableListOf<String>()

        ProjectsLink("kimi").link(global, local, log = { log += it })

        assertTrue(Files.isSymbolicLink(local), "a collision must not refuse the migration")
        assertEquals("global copy", Files.readString(global.resolve("-home-x/abc.jsonl")))
        assertEquals("head copy", Files.readString(global.resolve("-home-x/abc.jsonl.from-kimi")))
        assertEquals("only on the head", Files.readString(global.resolve("-home-x/fresh.jsonl")))
        assertTrue(
            log.any { it.contains("parked") && it.contains("abc.jsonl.from-kimi") },
            "the parked copy must be named once in the log, got $log",
        )
    }

    @Test
    fun `a parked name already taken refuses before anything links`(@TempDir tmp: Path) {
        val global = tmp.resolve("global-projects")
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "head copy")
        write(local.resolve("-home-y/fresh.jsonl"), "only on the head")
        write(global.resolve("-home-x/abc.jsonl"), "global copy")
        write(global.resolve("-home-x/abc.jsonl.from-kimi"), "an older parked copy")
        val log = mutableListOf<String>()

        ProjectsLink("kimi").link(global, local, log = { log += it })

        assertTrue(Files.isDirectory(local, NOFOLLOW_LINKS), "the real head dir stays")
        assertFalse(Files.exists(global.resolve("-home-y")), "nothing may link before preflight passes")
        assertEquals(1, nlink(local.resolve("-home-x/abc.jsonl")))
        assertTrue(
            log.any { it.contains("REFUSED") && it.contains("abc.jsonl.from-kimi") },
            "the refusal names it: $log",
        )
    }

    // A failure BEFORE the aside rename: nothing was ever moved, so the head tree is byte-identical
    // and global carries none of this run's links.
    @Test
    fun `a failing link rolls back and leaves the head tree byte-identical`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/a.jsonl"), "a")
        write(local.resolve("-home-x/a/subagents/s.jsonl"), "s")
        write(local.resolve("-home-y/z.jsonl"), "z")
        val before = snapshot(local)
        val failing = fsWith(link = { link, existing ->
            if (existing.fileName.toString() == "z.jsonl") {
                throw java.nio.file.FileSystemException(existing.toString(), null, "injected link failure")
            }
            Files.createLink(link, existing)
        })
        val log = mutableListOf<String>()

        ProjectsLink("kimi", failing).link(global, local, log = { log += it })

        assertTrue(Files.isDirectory(local, NOFOLLOW_LINKS), "the real head dir must survive a failed migration")
        assertEquals(before, snapshot(local))
        assertEquals(1, nlink(local.resolve("-home-x/a.jsonl")), "this run's global link was removed again")
        assertEquals(emptyMap<String, String>(), snapshot(global))
        assertFalse(Files.exists(global.resolve("-home-x")), "a global dir this run created is removed again")
        assertTrue(
            log.any { it.contains("unlinked") && it.contains("injected link failure") },
            "the rollback must log its cause, got $log",
        )
    }

    // A failure AT the promotion: the head tree was already renamed aside, so it is renamed back.
    @Test
    fun `a failing promotion renames the aside tree back`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-projects"))
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc")
        val before = snapshot(local)
        val promotionFails = fsWith(before = { source, target ->
            // the promotion is the staged link landing on dst; the rename-back in rollback targets dst too
            if (target == local && source.fileName.toString().contains("splice-link")) {
                throw java.nio.file.FileSystemException(target.toString(), null, "injected promotion failure")
            }
        })
        val log = mutableListOf<String>()

        ProjectsLink("kimi", promotionFails).link(global, local, log = { log += it })

        assertTrue(Files.isDirectory(local, NOFOLLOW_LINKS), "the aside tree must be back at the head path")
        assertEquals(before, snapshot(local))
        assertEquals(0, Files.list(tmp).use { s -> s.filter { it.fileName.toString().contains("migrating") }.count() })
        assertTrue(
            log.any { it.contains("put the head tree back") && it.contains("injected promotion failure") },
            "the rollback must say what it did and why, got $log",
        )
        // the links already made are the same inode, so a retry skips them and finishes the swap
        ProjectsLink("kimi").link(global, local, log = { log += it })
        assertTrue(Files.isSymbolicLink(local))
        assertEquals(before, snapshot(global))
        assertTrue(log.none { it.contains("parked") }, "a retry must not park what is already the same inode: $log")
    }

    @Test
    fun `a missing global projects dir is created rather than silently disabling sharing`(@TempDir tmp: Path) {
        val global = tmp.resolve("global-projects") // never created: a machine that never ran plain claude
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc")

        ProjectsLink("kimi").link(global, local)

        assertTrue(Files.isDirectory(global), "the global tree must be created, not skipped")
        assertTrue(Files.isSymbolicLink(local))
        assertEquals("abc", Files.readString(global.resolve("-home-x/abc.jsonl")))
    }

    @Test
    fun `a dangling global projects entry declines out loud and leaves the head dir alone`(@TempDir tmp: Path) {
        val global = Files.createSymbolicLink(tmp.resolve("global-projects"), tmp.resolve("gone"))
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc")
        val log = mutableListOf<String>()

        ProjectsLink("kimi").link(global, local, log = { log += it })

        assertTrue(Files.isDirectory(local), "the real head dir must survive")
        assertEquals("abc", Files.readString(local.resolve("-home-x/abc.jsonl")))
        assertTrue(log.any { it.contains("global-projects") }, "the decline must name the path, got $log")
    }

    @Test
    fun `an unreadable global cwd dir rolls back loudly instead of half-migrating`(@TempDir tmp: Path) {
        val global = tmp.resolve("global-projects")
        val local = tmp.resolve("projects")
        write(local.resolve("-home-x/abc.jsonl"), "abc")
        val locked = Files.createDirectories(global.resolve("-home-x"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        try {
            ProjectsLink("kimi").link(global, local, log = { log += it })
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }

        assertTrue(Files.isDirectory(local), "the real head dir must survive")
        assertEquals("abc", Files.readString(local.resolve("-home-x/abc.jsonl")))
        assertTrue(log.any { it.contains("failed") && it.contains("unlinked") }, "the decline must be loud, got $log")
    }
}
