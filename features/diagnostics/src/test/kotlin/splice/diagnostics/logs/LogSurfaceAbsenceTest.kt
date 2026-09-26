// DR-68 absence-class arms for the log display surfaces: an UNREADABLE log used to render as a
// silently blank dashboard/`splice logs` tail. The class law (display flavor): degrading to empty is
// allowed only for PROVEN absence; access-indeterminate must be said in-band, because the log itself
// is the reporting channel. The boot-log arms stay with DaemonSpawn in app (BootLogAbsenceTest).
package splice.diagnostics.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LogSurfaceAbsenceTest {

    @Test
    fun `an unreadable log names itself in-band - DR-68`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("logs"))
        val log = dir.resolve("daemon.log")
        Files.writeString(log, "[claudex] a line\n")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        val rendered = try {
            LogFileSource(log).tail(5)
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        assertTrue(rendered.contains("logs unavailable"), rendered)
        assertTrue(rendered.contains(log.toString()), rendered)
    }

    @Test
    fun `a genuinely absent log stays the quiet empty tail - DR-68 control`(@TempDir tmp: Path) {
        assertEquals("", LogFileSource(tmp.resolve("absent.log")).tail(5))
    }

    // DR-68 redo (codex adversarial probe): the once-per-episode latch must re-arm on ANY healthy
    // stat — a zero-byte log is a healthy read, not a continuing unreadable episode. The pre-redo
    // `size > 0` guard (the DR-63 isNotEmpty scar reintroduced) warned once across
    // denied → healthy-empty → denied instead of once per denied episode.
    @Test
    fun `a healthy zero-byte stat re-arms the follow latch - DR-68`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("logs"))
        val log = dir.resolve("daemon.log")
        Files.writeString(log, "x")
        val warned = java.util.concurrent.atomic.AtomicBoolean(false)
        val cmd = LogsCommand(TerminalOutput(::println), TerminalOutput(System.err::println))
        val deny = PosixFilePermissions.fromString("---------")
        val allow = PosixFilePermissions.fromString("rwx------")

        Files.setPosixFilePermissions(dir, deny)
        val firstEpisode = try {
            capturingStdout { cmd.polledSize(log, warned) } to capturingStdout { cmd.polledSize(log, warned) }
        } finally {
            Files.setPosixFilePermissions(dir, allow)
        }
        assertTrue(firstEpisode.first.contains("unreadable"), "episode 1 warns: ${firstEpisode.first}")
        assertEquals("", firstEpisode.second, "the episode stays latched")

        Files.writeString(log, "")
        assertEquals("", capturingStdout { assertEquals(0L, cmd.polledSize(log, warned)) })

        Files.setPosixFilePermissions(dir, deny)
        val secondEpisode = try {
            capturingStdout { cmd.polledSize(log, warned) }
        } finally {
            Files.setPosixFilePermissions(dir, allow)
        }
        assertTrue(secondEpisode.contains("unreadable"), "a fresh episode after a healthy empty stat warns again")
    }

    private fun capturingStdout(block: () -> Unit): String {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out, true))
            block()
        } finally {
            System.setOut(savedOut)
        }
        return out.toString()
    }
}
