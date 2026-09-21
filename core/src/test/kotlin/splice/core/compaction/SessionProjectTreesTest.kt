// NEW: V4-130 — SessionProject's headless fallback searches every projects tree it is given, head
// trees first, instead of the one vanilla path it hardcoded (SessionProject.kt:24 before this row).
// The case that failed before: a session whose transcript sits only in a head's OWN tree.
package splice.core.compaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionProjectTreesTest {

    @TempDir
    lateinit var home: Path

    private fun transcript(projects: Path, session: String, cwd: Path) {
        val file = projects.resolve("-w-repo/$session.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"type":"user","sessionId":"$session","cwd":"$cwd"}""" + "\n")
    }

    @Test
    fun `a session only a head's own tree holds resolves, and the vanilla-only resolver misses it`() {
        val sessions = Files.createDirectories(home.resolve(".claude/sessions"))
        val vanilla = Files.createDirectories(home.resolve(".claude/projects"))
        val codex = home.resolve(".claude-codex/projects")
        val cwd = home.resolve("work/repo").toAbsolutePath()
        transcript(codex, "codex-1", cwd)
        assertNull(SessionProject(sessions, vanilla).projectFor("codex-1"), "the pre-V4-130 shape")
        assertEquals(cwd, SessionProject(sessions, vanilla, headProjectsDirs = listOf(codex)).projectFor("codex-1"))
    }

    @Test
    fun `a symlinked head tree and the vanilla tree are one walk, and still resolve`() {
        val sessions = Files.createDirectories(home.resolve(".claude/sessions"))
        val vanilla = Files.createDirectories(home.resolve(".claude/projects"))
        val kimi = Files.createDirectories(home.resolve(".claude-kimi"))
        Files.createSymbolicLink(kimi.resolve("projects"), vanilla)
        val cwd = home.resolve("work/other").toAbsolutePath()
        transcript(vanilla, "plain-1", cwd)
        val project = SessionProject(sessions, vanilla, headProjectsDirs = listOf(kimi.resolve("projects")))
        assertEquals(cwd, project.projectFor("plain-1"))
    }
}
