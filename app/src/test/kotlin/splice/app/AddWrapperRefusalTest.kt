// NEW: V4-255 — `splice add` as app composes it (AddWiring.add), run until the wrapper link: the real
// install verb refuses in a home with no launch shim, and the add must print the linker's own sentence.
// It printed "failure (message withheld: it may quote file bytes)" instead, because AddWrapperLink
// rendered every link failure through SafeFailureText, which withholds any IllegalStateException, and
// InstallRefused is one. Seen in the V4-251 pty run of the built jar's `splice add codex`.
package splice.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.DaemonRestart
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Path

class AddWrapperRefusalTest {

    private fun servingModels(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        mapOf("/v1" to "{}", "/v1/models" to """{"data":[{"id":"m"}]}""").forEach { (path, body) ->
            createContext(path) { ex ->
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }

    private fun stdoutOf(home: Path, block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val prevOut = System.out
        val prevHome = System.getProperty("user.home")
        System.setOut(PrintStream(buf, true))
        System.setProperty("user.home", home.toString())
        return try {
            block()
            buf.toString()
        } finally {
            System.setOut(prevOut)
            System.setProperty("user.home", prevHome)
        }
    }

    @Test
    fun `splice add prints the linker's own sentence when it cannot link the wrapper`(@TempDir home: Path) {
        val vars = mapOf(
            "SPLICE_CONFIG" to home.resolve("splice.toml").toString(),
            "SPLICE_BIN_DIR" to home.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to home.resolve("share").toString(),
            "SPLICE_CONTROL_PORT" to TestPorts.reserve().toString(),
            "FW_API_KEY" to "k",
        )
        val env = EnvReader { vars[it] }
        TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
        val server = servingModels()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        val args = listOf("api-key", "--name", "fw", "--base-url", base, "--model", "m:1000", "--yes")
        val out = try {
            stdoutOf(home) { runBlocking { AddWiring.add(DaemonRestart { true }).add(args, env) } }
        } finally {
            server.stop(0)
        }

        val shim = home.resolve("share").resolve("splice-launch")
        val wrapper = out.lines().firstOrNull { "wrapper" in it }
        assertTrue(
            wrapper?.contains("not linked (launch shim not found at $shim (run install.sh))") == true,
            "the wrapper line names the linker's refusal: $wrapper\n$out",
        )
    }
}
