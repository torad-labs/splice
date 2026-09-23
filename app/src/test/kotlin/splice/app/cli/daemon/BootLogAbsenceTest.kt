// DR-68 absence-class arms for the boot log, split from LogSurfaceAbsenceTest when the log display
// surfaces moved to features/diagnostics (LAYOUT-01): an unreadable boot log used to read as a silent
// cold-start. Degrading to quiet is allowed only for PROVEN absence; access-indeterminate is said.
package splice.app.cli.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.daemonclient.DaemonHealth
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class BootLogAbsenceTest {

    // StatePaths' default root rides user.home (saved/restored, the AdminSupportTest idiom for
    // JVM-global properties); logs/ occupied by a FILE makes the boot-log read fail with a
    // present-but-unreadable class (NotDirectory), no chmod needed.
    //
    // V4-177: the fake home has no `<root>/state` under either root name, so StatePaths takes the
    // current default. The root is spelled here rather than imported — it is :core-internal, and
    // the module law is not a thing a test gets an exemption from. A divergence fails LOUDLY:
    // the boot-log read would find nothing and the 'unreadable' assertion below would miss.
    @Test
    fun `an unreadable boot log is said, not swallowed - DR-68`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".splice"))
        Files.writeString(tmp.resolve(".splice").resolve("logs"), "not a directory")
        val printed = withHomeCapturingStdout(tmp) { DaemonSpawn(DaemonHealth()).printBootLogTail() }
        assertTrue(printed.contains("boot log"), printed)
        assertTrue(printed.contains("unreadable"), printed)
    }

    @Test
    fun `a genuinely absent boot log stays quiet - DR-68 control`(@TempDir tmp: Path) {
        val printed = withHomeCapturingStdout(tmp) { DaemonSpawn(DaemonHealth()).printBootLogTail() }
        assertEquals("", printed)
    }

    private fun withHomeCapturingStdout(home: Path, block: () -> Unit): String {
        val savedHome = System.getProperty("user.home")
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        try {
            System.setProperty("user.home", home.toString())
            System.setOut(PrintStream(out, true))
            block()
        } finally {
            System.setOut(savedOut)
            System.setProperty("user.home", savedHome)
        }
        return out.toString()
    }
}
