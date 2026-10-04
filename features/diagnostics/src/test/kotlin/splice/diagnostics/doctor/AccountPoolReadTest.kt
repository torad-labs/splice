// NEW: the daemon's account projection as the CLI reads it, one cause per arm. The read returned null
// for no key, a refused key, nothing answering and a body of another shape alike, and doctor's row
// read "the daemon's /api/auth could not be read (mgmt key?)": a Warn with no fix and a guess for a
// reason (Hitstop's #271 critique, relayed by console). The daemon here is a two-route HttpServer; the
// mgmt key sits in a temp state dir the env points at.
package splice.diagnostics.doctor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

private const val POOLED = """{"codex":{"account_pool":{"accounts":[{"label":"work"}],"selected_label":"work"}}}"""

class AccountPoolReadTest {

    @TempDir
    lateinit var tmp: Path

    private var daemon: HttpServer? = null

    @AfterEach
    fun stop() {
        daemon?.stop(0)
    }

    /** A daemon whose /api/auth answers [status] with [body]; returns its port. */
    private fun daemonAnswering(status: Int, body: String): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/health") { exchange ->
            val bytes = """{"version":"test"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        daemon = server
        return server.address.port
    }

    private fun env(withKey: Boolean, port: Int = 0): EnvReader {
        val state = Files.createDirectories(tmp.resolve("state"))
        if (withKey) Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
        val env = mapOf(
            "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
            "SPLICE_STATE_DIR" to state.toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
        return EnvReader { env[it] }
    }

    private fun unread(port: Int, withKey: Boolean = true): AccountPoolsRead.Unread =
        assertInstanceOf(AccountPoolsRead.Unread::class.java, JdkAccountPoolRead()(port, env(withKey, port)))

    @Test
    fun `each reason the projection went unread is its own sentence, with the remedy that fits`() {
        val noKey = unread(daemonAnswering(200, POOLED), withKey = false)
        assertTrue(noKey.reason.contains("no management key") && noKey.reason.contains("mgmt-key check"), noKey.reason)
        assertEquals(null, noKey.fix, "the mgmt-key check carries that fix")
        stop()

        val refused = unread(daemonAnswering(401, """{"error":"unauthorized"}"""))
        assertTrue(refused.reason.contains("refused this shell's management key (HTTP 401)"), refused.reason)
        assertEquals(null, refused.fix, "no restart of ours makes two state dirs agree")
        stop()

        val failing = unread(daemonAnswering(503, """{"error":"x"}"""))
        assertEquals("the daemon's /api/auth answered HTTP 503", failing.reason)
        assertEquals("splice logs", failing.fix)
        stop()

        val otherShape = unread(daemonAnswering(200, "[]"))
        assertTrue(otherShape.reason.contains("not with the account projection"), otherShape.reason)
        assertEquals(FIX_RESTART, otherShape.fix)
        stop()

        val silent = unread(TestPorts.reserve())
        assertTrue(silent.reason.startsWith("the daemon's /api/auth did not answer ("), silent.reason)
        assertEquals("splice logs", silent.fix)
    }

    @Test
    fun `a readable projection is the pools, keyed by head`() {
        val port = daemonAnswering(200, POOLED)
        val read = JdkAccountPoolRead()(port, env(withKey = true, port))
        assertTrue(read is AccountPoolsRead.Read && read.pools.keys == setOf("codex"), "$read")
    }

    /** RED before: the row was WARN "the daemon's /api/auth could not be read (mgmt key?)" with no fix. */
    @Test
    fun `doctor's accounts row carries the cause and its fix, never a guess`() {
        val port = daemonAnswering(503, """{"error":"x"}""")
        val run = DoctorTestPorts.doctor().collect(env(withKey = true, port))
        val row = run.sections.toMap().getValue("accounts").single()
        assertEquals(CheckStatus.WARN, row.status)
        assertEquals("the daemon's /api/auth answered HTTP 503", row.detail)
        assertEquals("splice logs", row.fix)
    }
}
