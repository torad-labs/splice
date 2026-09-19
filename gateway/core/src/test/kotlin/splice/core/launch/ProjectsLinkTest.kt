// NEW: V4-115 head-private transcripts (2026-09-17). This file replaces the 14-test suite that
// pinned the OPPOSITE behaviour — the hardlink merge of a head's projects tree into the operator's
// vanilla ~/.claude/projects, the swap to a symlink, the straggler sweep and the two rollbacks
// (commit 91d68f3e). That design is gone: 95 head transcripts ended up in the vanilla tree and the
// vanilla client could not restore its own sessions. What is pinned now is the migration that gets a
// head back to a tree of its own — un-link a symlink, keep a real dir, create an absent one, preserve
// anything unexpected — and that the un-link never touches the tree the link pointed at.
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

class ProjectsLinkTest {

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** Name -> content for every entry below [root], walked WITHOUT following symlinks. */
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

    /** The real fs with one hook: [unlink] can fail deterministically on the delete. */
    private fun fsWith(unlink: (link: Path) -> Unit = { path -> Files.delete(path) }) = object : ProjectsFs {
        override fun deleteSymbolicLink(link: Path) = unlink(link)

        override fun createDirectories(dir: Path): Path = Files.createDirectories(dir)
    }

    @Test
    fun `a symlink becomes a real empty dir and the tree it pointed at is untouched`(@TempDir tmp: Path) {
        val vanilla = Files.createDirectories(tmp.resolve("vanilla/projects"))
        val foreign = write(vanilla.resolve("-home-x/other-head.jsonl"), "someone else's history")
        val dst = tmp.resolve("head/projects")
        Files.createDirectories(dst.parent)
        Files.createSymbolicLink(dst, vanilla)
        val log = mutableListOf<String>()

        assertTrue(ProjectsLink().ensurePrivate(dst, log = { log += it }))

        assertFalse(Files.isSymbolicLink(dst), "the link must be gone")
        assertTrue(Files.isDirectory(dst, NOFOLLOW_LINKS), "a real dir must take its place")
        val entries = Files.list(dst).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(emptyList<String>(), entries, "the replacement starts EMPTY: no history is moved")
        assertEquals("someone else's history", Files.readString(foreign))
        assertEquals(1, Files.getAttribute(foreign, "unix:nlink") as Int, "the linked tree must not be hardlinked")
        val unlinkLogged = log.any { it.contains("UNLINKED") && it.contains(vanilla.toString()) }
        assertTrue(unlinkLogged, "the un-link names the target: $log")
    }

    @Test
    fun `a real head projects dir is left exactly as found`(@TempDir tmp: Path) {
        val dst = Files.createDirectories(tmp.resolve("head/projects"))
        write(dst.resolve("-home-x/mine.jsonl"), "my history")
        val before = snapshot(dst)
        val log = mutableListOf<String>()

        assertTrue(ProjectsLink().ensurePrivate(dst, log = { log += it }))

        assertEquals(before, snapshot(dst), "a real head tree is already private and must not be touched")
        assertEquals(emptyList<String>(), log, "nothing to say about the steady state")
    }

    @Test
    fun `an absent projects dir is created`(@TempDir tmp: Path) {
        val dst = tmp.resolve("head/projects")
        Files.createDirectories(dst.parent)

        assertTrue(ProjectsLink().ensurePrivate(dst, log = {}))

        assertTrue(Files.isDirectory(dst, NOFOLLOW_LINKS), "Claude Code needs a real tree from the next message on")
    }

    @Test
    fun `unexpected non-directory content is preserved and named`(@TempDir tmp: Path) {
        val dst = tmp.resolve("head/projects")
        write(dst, "not a directory")
        val log = mutableListOf<String>()

        assertFalse(ProjectsLink().ensurePrivate(dst, log = { log += it }))

        assertEquals("not a directory", Files.readString(dst))
        val declined = log.any { it.contains("NOT made head-private") && it.contains(dst.toString()) }
        assertTrue(declined, "the decline names it: $log")
    }

    // DR-39: the caller's contract is that ensurePrivate logs its own declines — a delete that throws
    // must reach the daemon log, not vanish into a swallowed Result.
    @Test
    fun `a failing unlink is logged with its cause and leaves the head path alone`(@TempDir tmp: Path) {
        val vanilla = Files.createDirectories(tmp.resolve("vanilla"))
        val dst = tmp.resolve("head/projects")
        Files.createDirectories(dst.parent)
        Files.createSymbolicLink(dst, vanilla)
        val failing = fsWith(unlink = { path ->
            throw java.nio.file.FileSystemException(path.toString(), null, "injected unlink failure")
        })
        val log = mutableListOf<String>()

        ProjectsLink(failing).ensurePrivateOrLog(dst, log = { log += it })

        assertTrue(Files.isSymbolicLink(dst), "a failed unlink must leave the path exactly as it was")
        assertTrue(
            log.any { it.contains("could not be made head-private") && it.contains("injected unlink failure") },
            "the throw must be logged with its cause: $log",
        )
    }

    // The materializer end: a head is guaranteed a real projects tree REGARDLESS of policy — the
    // un-link migration must run even for a head whose share list never mentions projects, because a
    // policy is not what left the link there and a policy must not be able to keep it.
    @Test
    fun `the materializer gives every head a real projects dir even with an empty share policy`(@TempDir home: Path) {
        val vanilla = Files.createDirectories(home.resolve(".claude").resolve("projects"))
        write(vanilla.resolve("-home-x/other.jsonl"), "other")
        val head = Files.createDirectories(home.resolve(".claude-codex"))
        Files.createSymbolicLink(head.resolve("projects"), vanilla)

        ClaudeConfigMaterializer(home).materialize(
            MaterializeSpec(
                configDir = head,
                policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
                availableModelIds = listOf("m"),
                defaultModel = "m",
                modelOptionsCache = buildJsonObject { put("cache", "x") },
                statuslineCommand = "curl :3096/statusline",
                headKey = "codex",
            ),
        )

        val headProjects = head.resolve("projects")
        assertFalse(Files.isSymbolicLink(headProjects), "an empty policy must not keep the vanilla link")
        assertTrue(Files.isDirectory(headProjects, NOFOLLOW_LINKS))
        assertEquals("other", Files.readString(vanilla.resolve("-home-x/other.jsonl")), "vanilla content is not moved")
    }
}
