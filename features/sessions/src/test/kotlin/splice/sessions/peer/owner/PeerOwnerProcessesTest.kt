package splice.sessions.peer.owner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A peer registered from a harness records that harness as its owner, so its helper ends with it. Claude Code's
 *  native installer runs the harness from `~/.local/share/claude/versions/<version>`, so the executable's own name
 *  is a version number, and an updated install leaves the running one's link reading `<version> (deleted)`. */
class PeerOwnerProcessesTest {
    private val parent: Long = ProcessHandle.current().parent().orElseThrow().pid()

    private fun procWithParentExe(proc: Path, exe: String): PeerOwnerProcesses {
        val dir = Files.createDirectories(proc.resolve(parent.toString()))
        Files.createSymbolicLink(dir.resolve("exe"), Path.of(exe))
        return PeerOwnerProcesses(proc = proc)
    }

    @Test
    fun `a Claude Code harness run from its versioned install is the registration's owner`(@TempDir proc: Path) {
        val processes = procWithParentExe(proc, "/home/someone/.local/share/claude/versions/2.1.312")
        assertEquals(parent, processes.ancestor(setOf("codex", "claude"))?.pid)
    }

    @Test
    fun `a Claude Code harness whose version was replaced by an update is still the owner`(@TempDir proc: Path) {
        val processes = procWithParentExe(proc, "/home/someone/.local/share/claude/versions/2.1.312 (deleted)")
        assertEquals(parent, processes.ancestor(setOf("codex", "claude"))?.pid)
    }

    @Test
    fun `a harness installed under its own name is the owner`(@TempDir proc: Path) {
        val processes = procWithParentExe(proc, "/usr/local/bin/codex")
        assertEquals(parent, processes.ancestor(setOf("codex", "claude"))?.pid)
    }

    @Test
    fun `a versions folder that is not Claude Code's names no owner`(@TempDir proc: Path) {
        val processes = procWithParentExe(proc, "/opt/node/versions/2.1.312")
        assertNull(processes.ancestor(setOf("codex", "claude")))
    }
}
