// NEW: moved from app's AdminSupportTest with the code it tests (LAYOUT-01) — the control-port
// resolution and the two liveness probes now live here, and AdminSupport only delegates to them.
package splice.daemonclient

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.GATEWAY_VERSION
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class DaemonClientTest {

    @Test
    fun `controlPort diagnoses corrupt production topology before using defaults`(@TempDir tmp: Path) {
        val config = tmp.resolve("config/splice/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "[daemon\n")
        val env = EnvReader { name ->
            mapOf(
                "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
                "CLAUDEX_STATE_DIR" to tmp.resolve("state").toString(),
            )[name]
        }
        val errors = StringBuilder()

        DaemonSettings(TerminalOutput { errors.appendLine(it) }).controlPort(env)

        val diagnostic = errors.toString()
        assertTrue(diagnostic.contains("could not read $config"), diagnostic)
        assertTrue(diagnostic.contains("using default ports; a running daemon may appear stopped"), diagnostic)
    }

    // V4-109: the daemon honours [daemon].state_dir (DaemonProcess), so the CLI's key and port reads
    // must resolve the same dir; `splice restart` found no key when they did not. The environment
    // points at an EMPTY state dir, so a reader that ignores the topology finds nothing there, never
    // the operator's real key.
    @Test
    fun `the CLI reads the key and the state port where a declared state_dir put them`(@TempDir tmp: Path) {
        val declared = Files.createDirectories(tmp.resolve("declared"))
        Files.writeString(declared.resolve("mgmt-key"), "planted-key")
        Files.writeString(declared.resolve("config.json"), """{"controlPort": 47123}""")
        val config = tmp.resolve("splice.toml")
        Files.writeString(config, "[daemon]\nstate_dir = \"$declared\"\n")
        val env = EnvReader { name ->
            mapOf(
                "SPLICE_CONFIG" to config.toString(),
                "SPLICE_STATE_DIR" to Files.createDirectories(tmp.resolve("env-state")).toString(),
            )[name]
        }

        assertEquals(MgmtKeyRead.Present("planted-key"), MgmtKeyFile().read(env))
        assertEquals(47123, DaemonSettings(TerminalOutput {}).controlPort(env))
    }

    @Test
    fun `daemon probe requires the versioned splice HTTP health contract`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = """{"ok":true,"version":"unrelated-service"}"""
        server.createContext("/health") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertFalse(DaemonHealth().daemonUp(server.address.port))
            body = """{"ok":true,"version":"$GATEWAY_VERSION"}"""
            assertTrue(DaemonHealth().daemonUp(server.address.port))
        } finally {
            server.stop(0)
        }
    }

    // BS-4 DEFECT B: "/health stopped answering" is not proof the old daemon freed its control port,
    // so the cold-start gate reads the port itself. A bound-but-not-serving listener must read bound.
    @Test
    fun `controlPortBound reports a bound port as bound and a freed port as free`() {
        val server = ServerSocket(0)
        try {
            assertTrue(DaemonHealth().controlPortBound(server.localPort), "an accepting listener is bound")
        } finally {
            server.close()
        }
        assertFalse(DaemonHealth().controlPortBound(server.localPort), "a closed port refuses — free")
    }
}
