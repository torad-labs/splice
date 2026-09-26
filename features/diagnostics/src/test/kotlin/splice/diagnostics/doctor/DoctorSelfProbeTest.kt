// NEW: V4-230 — what doctor's daemon row says about a listener it probed, by what the probe found,
// and the daemon's own doctor, which reads the daemon's answers in process and probes nothing. The
// probe read refused, timed out and non-2xx alike as "nothing answering", so a daemon too busy to
// answer /health in 400ms read "stopped (starts on first launch)": in the console's own doctor, whose
// handler was that busy daemon, 3 page loads in 6 said so, and once raised a false WARN (2026-09-25).
package splice.diagnostics.doctor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

private const val POOLED = """{"codex":{"account_pool":{"accounts":[{"label":"work"}],"selected_label":"work"}}}"""

class DoctorSelfProbeTest {

    @TempDir
    lateinit var tmp: Path

    private var daemon: HttpServer? = null

    /** Holds every /health answer until the test is done with the probe. */
    private val release = CountDownLatch(1)

    @AfterEach
    fun stop() {
        release.countDown()
        daemon?.stop(0)
    }

    /** A listener that accepts /health and answers only once [release] opens, which is after the
     *  probe's window has closed: a daemon too busy to answer in time. */
    private fun healthHeld(): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            release.await()
            val bytes = """{"version":"test","heads":0,"readyHeads":0,"failedHeads":0}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        daemon = server
        return server.address.port
    }

    private fun env(port: Int): EnvReader {
        val values = mapOf(
            "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
            "SPLICE_STATE_DIR" to Files.createDirectories(tmp.resolve("state")).toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
        return EnvReader { values[it] }
    }

    private fun daemonRow(env: EnvReader): DoctorCheck =
        DoctorTestPorts.doctor().collect(env).sections.single { it.first == CHECK_DAEMON }.second.first()

    @Test
    fun `a daemon slow to answer health reads running and slow, with the time it waited`() {
        val port = healthHeld()
        val run = DoctorTestPorts.doctor().collect(env(port))
        val row = run.sections.toMap().getValue(CHECK_DAEMON).first()
        assertEquals(CheckStatus.WARN, row.status, row.detail)
        assertTrue(row.detail.startsWith("running on :$port, slow to answer"), row.detail)
        assertTrue(row.detail.contains("400ms"), row.detail)
        // The reads that would wait on it again say why they did not, never that the daemon is stopped.
        val accounts = run.sections.toMap().getValue("accounts").single()
        assertEquals("skipped (daemon slow to answer: /health waited 400ms)", accounts.detail)
    }

    @Test
    fun `the daemon's own doctor reads the answers it gave itself, never its own port`() {
        // Its port answers late, as a daemon busy serving this very request answered itself; and this
        // state dir holds no management key, so over loopback the runtime and accounts reads would skip.
        val port = healthHeld()
        val version = DaemonHealth().cliVersion()
        val answers = DaemonAnswers(
            health = """{"version":"$version","heads":1,"readyHeads":1,"failedHeads":0}""",
            heads = """{"heads":[{"key":"codex","health":{"localOriginErrors":0,"providerErrors":0}}]}""",
            auth = POOLED,
        )
        val run = DoctorTestPorts.doctor().collect(env(port), answers = answers)
        val sections = run.sections.toMap()
        assertEquals(
            DoctorCheck(CHECK_DAEMON, CheckStatus.OK, "running $version on :$port"),
            sections.getValue(CHECK_DAEMON).first(),
        )
        assertEquals(
            CheckStatus.OK,
            sections.getValue("runtime").single { it.name == "head codex errors" }.status,
            sections.getValue("runtime").toString(),
        )
        assertEquals(setOf("codex"), run.accountPools.keys)
    }

    @Test
    fun `a port nothing listens on reads stopped`() {
        val row = daemonRow(env(TestPorts.reserve()))
        assertEquals(CheckStatus.INFO, row.status, row.detail)
        assertEquals("stopped (starts on first launch)", row.detail)
    }
}
