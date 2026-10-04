// NEW: V4-220 item 6b — doctor's line for a client (Claude) head says what upstream last answered the
// login splice forwards, read off the running daemon's /api/auth. It said "client-native … no key to
// set" and OK even while upstream rejected every forwarded turn. The daemon here is a one-route
// HttpServer answering /api/auth; the mgmt key sits in a temp state dir the env points at, and the
// config home is empty, so nothing on this machine can reach the run.
package splice.diagnostics.doctor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelEntry
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "claude-splice"
private const val REJECTED_AT = 1_790_000_000_000L

class DoctorClientVerdictTest {

    private var daemon: HttpServer? = null

    @AfterEach
    fun stop() {
        daemon?.stop(0)
    }

    private val topology = Topology(
        daemon = DaemonConfig(controlPort = 4123),
        providers = mapOf(
            "anthropic" to ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.anthropic.com",
                auth = AuthConfig("client"),
                models = listOf(ModelEntry("claude-fable-5", contextWindow = 200_000)),
            ),
        ),
        heads = mapOf(HEAD to HeadConfig("anthropic", 4599, "$HEAD--", "claude-fable-5")),
    )

    /** A daemon whose /api/auth answers [verdict] for the client head; returns its port. */
    private fun daemonSaying(verdict: String): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth") { exchange ->
            val body = """{"$HEAD":{"kind":"client","login":"manual","present":${!verdict.contains("rejected")},""" +
                """"verdict":$verdict}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        daemon = server
        return server.address.port
    }

    private fun authChecks(tmp: Path, port: Int): List<DoctorCheck> {
        val state = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
        val env = mapOf(
            "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
            "SPLICE_STATE_DIR" to state.toString(),
        )
        val running = DaemonSnapshot(port, DaemonProbe.HealthProbe.Up(DaemonProbe.HealthView("test", 1, 1, 0)))
        return DoctorAuth(TerminalOutput {})
            .authChecks(
                DoctorTopology.Parsed(topology),
                EnvReader { env[it] },
                running,
                LoopbackDaemon(JdkAccountPoolRead()),
            )
    }

    /** RED before V4-220: the line was the declaration, OK, whatever the daemon had seen. */
    @Test
    fun `a client head the daemon saw rejected is the failure, with the login fix`(@TempDir tmp: Path) {
        val port = daemonSaying("""{"state":"rejected","at_epoch_ms":$REJECTED_AT}""")

        val line = authChecks(tmp, port).single { it.name == HEAD }

        assertEquals(CheckStatus.FAIL, line.status, "the only head does not work: $line")
        assertTrue(line.detail.contains("upstream rejected the forwarded Claude login"), line.detail)
        assertTrue(line.detail.contains("2026-09-21T"), "the verdict is dated: ${line.detail}")
        assertEquals("run $HEAD, then /login inside it", line.fix)
    }

    @Test
    fun `a client head the daemon saw accepted is OK and says so`(@TempDir tmp: Path) {
        val port = daemonSaying("""{"state":"accepted","at_epoch_ms":$REJECTED_AT}""")

        val line = authChecks(tmp, port).single { it.name == HEAD }

        assertEquals(CheckStatus.OK, line.status, line.toString())
        assertTrue(line.detail.contains("upstream accepted the forwarded login"), line.detail)
    }

    @Test
    fun `a client head no forwarded turn was answered on is OK and unverified`(@TempDir tmp: Path) {
        val port = daemonSaying("""{"state":"unverified"}""")

        val line = authChecks(tmp, port).single { it.name == HEAD }

        assertEquals(CheckStatus.OK, line.status, line.toString())
        assertTrue(line.detail.contains("unverified"), line.detail)
    }
}
