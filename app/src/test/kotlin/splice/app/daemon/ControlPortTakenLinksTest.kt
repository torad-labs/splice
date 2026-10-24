// NEW: LAN share, round 17 of PR #419's review. The goal: a second daemon, or one whose control port another process
// holds, reaches no machine this one joined. It exits without its control port, so it must not look at, offer to or
// collect from those machines, or check on them, beside the daemon that is running: two links to one machine would
// compete for its notes and its acknowledgements, and the mirrors of one would outlive the process that made them. A
// real Daemon over a splice.toml that names one joined machine, whose token and Sessions switch are kept here, with its
// control port held by the test; the joined machine is a listener that counts every connection made to it.
package splice.app.daemon

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.util.LogSink
import splice.share.ShareState
import splice.share.remote.RemotesStore
import splice.topology.TopologyLoader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// why: what the daemon's splice.toml keys the joined machine by.
private const val JOINED = "torad"

// why: a link looks, and its machine is checked, at once and then every few seconds; three seconds covers the first
// of each on a loaded machine.
private const val QUIET_MS = 3_000L

// why: how often the test reads the count while it waits out QUIET_MS.
private const val COUNT_POLL_MS = 50L

class ControlPortTakenLinksTest {

    @Test
    fun `a daemon that does not take its control port reaches no machine it joined`(@TempDir tmp: Path) {
        val controlPort = TestPorts.reserve()
        val machine = ServerSocket(TestPorts.reserve(), 50, InetAddress.getLoopbackAddress())
        val reached = AtomicInteger()
        val counting = thread(isDaemon = true) {
            try {
                while (true) machine.accept().use { reached.incrementAndGet() }
            } catch (_: SocketException) {
                // the test closed it
            }
        }
        val file = tmp.resolve("splice.toml")
        Files.writeString(
            file,
            """
            [daemon]
            control_port = $controlPort

            [remotes.$JOINED]
            url = "https://127.0.0.1:${machine.localPort}"
            fingerprint = "sha256:${"0".repeat(64)}"
            """.trimIndent() + "\n",
        )
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = RemotesStore(ShareState(paths).remotesFile)
        store.save(JOINED, "a-token")
        store.allowSessions(JOINED, true)
        val said = StringBuffer()
        val daemon = Daemon(
            TopologyLoader.parse(Files.readString(file)),
            paths,
            LogSink { said.append(it) },
            topologyPath = file,
        )

        try {
            ServerSocket(controlPort, 50, InetAddress.getLoopbackAddress()).use {
                runBlocking {
                    daemon.start()
                    val until = System.currentTimeMillis() + QUIET_MS
                    while (reached.get() == 0 && System.currentTimeMillis() < until) delay(COUNT_POLL_MS)
                }
            }
            assertTrue("control plane could not bind" in said, "the daemon found its control port taken: $said")
            assertEquals(0, reached.get(), "the machine it joined heard nothing from it")
        } finally {
            runBlocking { daemon.stop() }
            machine.close()
            counting.join()
        }
    }
}
