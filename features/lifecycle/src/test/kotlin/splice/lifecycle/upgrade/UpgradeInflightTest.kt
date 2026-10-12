// `splice upgrade` reads the daemon's in-flight count (v0.4.0, FEATURES.md §5): NoDaemon needs
// POSITIVE absence evidence — a refused connect on the control port — and every other failure to
// see the turns is Unknown, which the upgrade waits on or refuses, never treats as zero.
package splice.lifecycle.upgrade

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path

class UpgradeInflightTest {

    private fun env(tmp: Path) = EnvReader { name ->
        tmp.resolve("state").toString().takeIf { name == "CLAUDEX_STATE_DIR" }
    }

    @Test
    fun `a refused connect is the only NoDaemon, a listener that does not answer is Unknown`(@TempDir tmp: Path) {
        val env = env(tmp)
        MgmtKey(StatePaths(baseOverride = tmp.resolve("state"))).get()
        val free = TestPorts.reserve()
        assertEquals(InflightRead.NoDaemon, JdkUpgradeInflight(env, port = free, connectTimeoutMs = 500)())

        ServerSocket(0).use { listener ->
            val read = JdkUpgradeInflight(env, port = listener.localPort, connectTimeoutMs = 500)()
            assertTrue(read is InflightRead.Unknown, "something listens but never answers /api/heads: $read")
        }
    }

    @Test
    fun `a listener without a readable mgmt key is Unknown, not NoDaemon`(@TempDir tmp: Path) {
        ServerSocket(0).use { listener ->
            val read = JdkUpgradeInflight(env(tmp), port = listener.localPort, connectTimeoutMs = 500)()
            assertTrue(read is InflightRead.Unknown && read.reason.contains("mgmt key"), read.toString())
        }
    }

    private fun serving(body: String, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/heads") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the count is the sum of every head's gate inflight, and a head without one makes it Unknown`(
        @TempDir tmp: Path,
    ) {
        MgmtKey(StatePaths(baseOverride = tmp.resolve("state"))).get()
        serving("""{"heads":[{"key":"a","gate":{"inflight":2}},{"key":"b","gate":{"inflight":3}}]}""") { port ->
            assertEquals(InflightRead.Count(5), JdkUpgradeInflight(env(tmp), port)())
        }
        serving("""{"heads":[{"key":"a","gate":{"inflight":2}},{"key":"b"}]}""") { port ->
            val read = JdkUpgradeInflight(env(tmp), port)()
            assertTrue(read is InflightRead.Unknown && read.reason.contains("b"), read.toString())
        }
    }
}
