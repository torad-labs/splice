// V4-353: when splice can't start, it says why. The F1 walk's p6 (2026-09-27) read "splice: daemon did not come up;
// last boot output (…/daemon-boot.log):" followed by nothing, then "the daemon isn't running and couldn't be
// started.", while daemon.log held the reason ("control plane could not bind :47360 (Address already in use)").
// An empty boot log is no longer printed as a header over nothing: it is said, and daemon.log is named.
package splice.lifecycle.start.v4353

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.daemonclient.DaemonHealth
import splice.lifecycle.start.DaemonSpawn
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class BootLogReasonTest {

    @Test
    fun `an empty boot log says the daemon left no reason there and names daemon-log - V4-353`(@TempDir tmp: Path) {
        val logs = Files.createDirectories(tmp.resolve(".splice").resolve("logs"))
        Files.writeString(logs.resolve("daemon-boot.log"), "")

        val printed = withHomeCapturingStdout(tmp) { spawn().printBootLogTail() }

        assertFalse(printed.contains("last boot output"), "a header over an empty boot log: $printed")
        assertTrue(printed.contains("left no reason"), printed)
        assertTrue(printed.contains(logs.resolve("daemon.log").toString()), "daemon.log is named: $printed")
    }

    @Test
    fun `a boot log holding the reason prints its tail as before - V4-353 control`(@TempDir tmp: Path) {
        val logs = Files.createDirectories(tmp.resolve(".splice").resolve("logs"))
        val reason = "[daemon] control plane could not bind :47360 (Address already in use); another owns it, exiting"
        Files.writeString(logs.resolve("daemon-boot.log"), "$reason\n")

        val printed = withHomeCapturingStdout(tmp) { spawn().printBootLogTail() }

        assertTrue(printed.contains("last boot output"), printed)
        assertTrue(printed.contains("Address already in use"), printed)
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
