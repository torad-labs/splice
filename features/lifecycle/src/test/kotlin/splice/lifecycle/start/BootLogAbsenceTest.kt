// Absence classes for the boot log: an unreadable boot log is said, a proven-absent one stays quiet.
package splice.lifecycle.start

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.daemonclient.DaemonHealth
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class BootLogAbsenceTest {

    // StatePaths' default root rides the home (UserHome.within, V4-218); logs/ occupied by a FILE makes
    // the boot-log read fail with a present-but-unreadable class (NotDirectory), no chmod needed.
    //
    // V4-177: the fake home has no `<root>/state` under either root name, so StatePaths takes the
    // current default. The root is spelled here rather than imported — it is :core-internal, and
    // the module law is not a thing a test gets an exemption from. A divergence fails LOUDLY:
    // the boot-log read would find nothing and the 'unreadable' assertion below would miss.
    @Test
    fun `an unreadable boot log is said, not swallowed`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".splice"))
        Files.writeString(tmp.resolve(".splice").resolve("logs"), "not a directory")
        val printed = withHomeCapturingStdout(tmp) { spawn().printBootLogTail() }
        assertTrue(printed.contains("boot log"), printed)
        assertTrue(printed.contains("unreadable"), printed)
    }

    @Test
    fun `a genuinely absent boot log stays quiet`(@TempDir tmp: Path) {
        val printed = withHomeCapturingStdout(tmp) { spawn().printBootLogTail() }
        assertEquals("", printed)
    }

    private fun spawn() = DaemonSpawn(TerminalOutput(::println), DaemonHealth(), RunningJar { null })

    private fun withHomeCapturingStdout(home: Path, block: () -> Unit): String {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        try {
            UserHome.within(home) {
                System.setOut(PrintStream(out, true))
                block()
            }
        } finally {
            System.setOut(savedOut)
        }
        return out.toString()
    }
}
