// A restart stops a daemon that is too busy to answer /health in the probe's window. It used to take one for no daemon:
// the restart then cold-started onto a port the old daemon still held, and on a busy laptop `splice remote add` left
// that daemon serving without the command it had just linked.
package splice.lifecycle.restart

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SlowDaemonStopTest {

    /** Released when the busy daemon is told to shut down, or the test ends. */
    private val released = CountDownLatch(1)

    /** A daemon whose /health answers only once it is told to stop: busier than any probe waits for. */
    private fun busyDaemon(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/health") { ex ->
            released.await()
            val bytes = """{"version":"busy-daemon"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/daemon/shutdown") { ex ->
            ex.sendResponseHeaders(202, -1)
            ex.close()
            released.countDown()
            Thread { server.stop(0) }.start()
        }
        server.start()
        return server
    }

    private fun listening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: IOException) {
        false
    }

    @Test
    fun `a daemon too busy to answer health in time is stopped, not taken for none`(@TempDir tmp: Path) {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(stateDir.resolve("mgmt-key"), "the-key")
        val server = busyDaemon()
        val port = server.address.port
        val lines = mutableListOf<String>()
        val said = TerminalOutput { lines += it }
        val env = EnvReader { if (it == "CLAUDEX_STATE_DIR") stateDir.toString() else null }
        try {
            val stopped = RestartCommand(said, said, env, RunningJar { null })
                .stopIfRunning(port, emptyList(), waitForCompactions = false)
            assertTrue(stopped, lines.joinToString("\n"))
            assertTrue(lines.any { "stopping daemon (slow to answer) on :$port" in it }, lines.joinToString("\n"))
            assertFalse(listening(port), "the busy daemon still holds its port\n${lines.joinToString("\n")}")
        } finally {
            released.countDown()
            server.stop(0)
        }
    }
}
