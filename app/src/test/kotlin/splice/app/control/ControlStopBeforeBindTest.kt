// NEW: a stop that crosses the control server's ownership after startup adopted it and before it bound must refuse the
// bind. The stop used to be remembered only for a start already running, so a stop in that window recorded nothing, the
// resumed start bound the listener, and the daemon lock was released under a live port. The hold is exact: a management
// key file that is present but blank makes the key mint log loudly, and the mint is the first line of ControlServer.start.
package splice.app.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonBoundary
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.util.LogSink
import splice.lifecycle.restart.ShutdownDaemon
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val WAIT_S = 30L
private const val CONNECT_TIMEOUT_MS = 500

class ControlStopBeforeBindTest {

    @Test
    fun `a stop between the adoption and the bind refuses the bind and leaves no listener`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        Files.createDirectories(paths.mgmtKeyFile.parent)
        Files.writeString(paths.mgmtKeyFile, "")
        val held = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val holding = LogSink {
            held.countDown()
            resume.await()
        }
        val port = TestPorts.reserve()
        val auth = ControlAuth(MgmtKey(paths, holding), holding)
        val server = controlServerFor(port, emptyMap(), ConfigService(paths), auth)
        val ownership = ControlOwnership(DaemonBoundary(), LogSink { }, ShutdownDaemon { })
        var outcome: Result<Boolean>? = null
        val starting = thread { outcome = runCatching { runBlocking { ownership.bind(server, port) } } }

        assertTrue(held.await(WAIT_S, TimeUnit.SECONDS)) { "startup never reached the key mint" }
        ownership.close()
        resume.countDown()
        starting.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertTrue(outcome?.exceptionOrNull() is CancellationException) {
            "the bind must end cancelled after a stop, was: $outcome"
        }
        assertFalse(listens(port)) { "a listener is live on :$port after the stop refused the bind" }
    }

    private fun listens(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
