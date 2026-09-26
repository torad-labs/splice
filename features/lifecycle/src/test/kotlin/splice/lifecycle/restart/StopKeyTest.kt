// DR-174 arms for `splice restart`'s stop key, split from app's CliAuthPresenceTest when the restart
// verb moved to features/lifecycle (LAYOUT-01): a key it cannot READ and a key that is not there are
// two states with opposite remedies, and the restart path says which one it met.
package splice.lifecycle.restart

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class StopKeyTest {

    private fun <T> withDenied(dir: Path, block: () -> T): T = try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        block()
    } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
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

    /** A loopback /health so DaemonProbe.healthVersion answers and stopIfRunning reaches the key
     *  branch at all — without it the verb short-circuits on "nothing is running" and the arm
     *  would pass over code it never entered. */
    private fun runningDaemon(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val bytes = """{"version":"test-daemon"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    // DR-174: `splice restart` printed "mgmt-key not found at <path>" for a key it could not READ,
    // because AdminSupport.mgmtKey collapsed AccessDenied and absence into one null. The two states
    // have opposite remedies — one chmod versus a re-mint the operator cannot even perform while
    // the daemon holds the old key in memory — so the arm asserts the SENTENCE, not just the
    // refusal: both states correctly refuse to stop, and only the wording tells them apart.
    @Test
    fun `an unreadable mgmt key is not a missing one on the restart path - DR-174`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(stateDir.resolve("mgmt-key"), "the-real-key")
        val server = runningDaemon()
        val port = server.address.port
        var stopped = true
        val printed = try {
            capturingStdout {
                stopped = withDenied(stateDir) {
                    restart(stateDir).stopIfRunning(port, emptyList())
                }
            }
        } finally {
            server.stop(0)
        }

        assertFalse(stopped, "a key it cannot read is still not a key it can stop with")
        assertTrue(printed.contains("unreadable"), printed)
        assertTrue(
            printed.contains("nothing needs re-minting"),
            "the operator must be sent to permissions, not to re-create a key that exists: $printed",
        )
        assertFalse(
            printed.contains("not found"),
            "an existing key must never be reported as missing: $printed",
        )
    }

    @Test
    fun `a genuinely absent mgmt key still reports not found - DR-174 control`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        val server = runningDaemon()
        val port = server.address.port
        var stopped = true
        val printed = try {
            capturingStdout { stopped = restart(stateDir).stopIfRunning(port, emptyList()) }
        } finally {
            server.stop(0)
        }

        assertFalse(stopped, printed)
        // The control that keeps the fix from becoming "call everything unreadable": proven absence
        // must keep its own, different sentence, or the arm above would pass on a constant string.
        assertTrue(printed.contains("not found"), printed)
        assertFalse(printed.contains("unreadable"), printed)
    }

    private fun stateEnv(stateDir: Path) = EnvReader { name ->
        if (name == "CLAUDEX_STATE_DIR") stateDir.toString() else null
    }

    /** The verb over a temp state dir; its lines reach System.out, which [capturingStdout] reads. */
    private fun restart(stateDir: Path) = RestartCommand(
        TerminalOutput(::println),
        TerminalOutput(System.err::println),
        stateEnv(stateDir),
        RunningJar { null },
    )
}
