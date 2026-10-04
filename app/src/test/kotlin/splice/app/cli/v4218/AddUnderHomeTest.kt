// NEW: V4-218 — `splice add` run the way a sandbox runs it: a JVM whose HOME names one directory while its
// user.home (the passwd entry's home) names another. Every file the verb writes belongs under HOME. Before
// V4-218 every path read user.home, so this run wrote its splice.toml into the other directory: a sandbox,
// CI or second-profile run wrote into the real home's config. A child JVM because HOME is the process's
// environment, which no in-process test can give a different value.
package splice.app.cli.v4218

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.CommandLiveTurn
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.streams.toList

class AddUnderHomeTest {

    @Test
    fun `splice add writes under HOME and nothing under user home - V4-218`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val passwdHome = Files.createDirectories(tmp.resolve("passwd-home"))
        val server = servingModels()
        val (exit, output) = try {
            add(tmp, home, passwdHome, "http://127.0.0.1:${server.address.port}/v1")
        } finally {
            server.stop(0)
        }

        val strays = Files.walk(passwdHome).use { paths ->
            paths.filter { it != passwdHome }.map(passwdHome::relativize).toList()
        }
        assertEquals(emptyList<Path>(), strays, "written under user.home instead of HOME:\n$output")
        val config = home.resolve(".config/splice/splice.toml")
        assertTrue(config.exists(), "no splice.toml under HOME:\n$output")
        assertTrue("fw" in TopologyLoader.parse(Files.readString(config)).heads, "no fw head in $config:\n$output")
        assertEquals(0, exit, output)
    }

    @Test
    fun `the live checker runs the installed command and requires its answer`(@TempDir tmp: Path) {
        val arguments = tmp.resolve("arguments")
        val wrapper = script(tmp, "printf '%s\\n' \"\$@\" > '$arguments'\nprintf 'pong\\n'\n")
        val result = CommandLiveTurn().check(wrapper)
        assertTrue(result.ok, result.detail)
        val args = Files.readAllLines(arguments)
        assertEquals("-p", args[0])
        assertEquals("Reply with the single word pong.", args[1])
        assertTrue("--no-session-persistence" in args)
        assertTrue("--strict-mcp-config" in args)
        assertEquals("", args[args.indexOf("--tools") + 1])
        val empty = script(tmp, "exit 0\n")
        assertFalse(CommandLiveTurn().check(empty).ok, "exit zero without an answer is not a passing check")
        val wrong = script(tmp, "printf 'not pong\\n'\n")
        assertFalse(CommandLiveTurn().check(wrong).ok, "merely mentioning the requested word is not an answer")
    }

    @Test
    fun `a failed or stalled live checker returns a diagnosis without raw child output`(@TempDir tmp: Path) {
        val failed = script(tmp, "printf 'synthetic-private-token' >&2\nexit 9\n")
        val result = CommandLiveTurn().check(failed)
        assertFalse(result.ok)
        assertTrue(result.detail.contains("exited 9"), result.detail)
        assertFalse(result.detail.contains("synthetic-private-token"))
        val stalled = script(tmp, "exec sleep 10\n")
        val timeout = CommandLiveTurn(timeoutMs = 20).check(stalled)
        assertFalse(timeout.ok)
        assertTrue(timeout.detail.contains("time limit"), timeout.detail)
        assertFalse(CommandLiveTurn().check(tmp.resolve("missing")).ok)
    }

    @Test
    fun `the live checker finds the command in a custom install directory`(@TempDir tmp: Path) {
        script(tmp, "printf 'pong\\n'\n")
        val env = EnvReader { name ->
            when (name) {
                "HOME", "SPLICE_BIN_DIR" -> tmp.toString()
                else -> null
            }
        }
        val result = CommandLiveTurn().invoke("check-command", env)
        assertTrue(result.ok, result.detail)
    }

    @Test
    fun `an inherited child output stream cannot extend the check deadline`(@TempDir tmp: Path) {
        val wrapper = script(tmp, "sleep 2 &\nprintf 'pong\\n'\n")
        val start = System.nanoTime()
        val result = CommandLiveTurn(timeoutMs = 100).check(wrapper)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 1000, "inherited output held the check for $elapsedMs ms: ${result.detail}")
    }

    private fun script(tmp: Path, body: String): Path {
        val wrapper = tmp.resolve("check-command")
        Files.writeString(wrapper, "#!/bin/sh\n$body")
        assertTrue(wrapper.toFile().setExecutable(true))
        return wrapper
    }

    private fun add(tmp: Path, home: Path, passwdHome: Path, baseUrl: String): Pair<Int, String> {
        val output = tmp.resolve("add.out")
        val builder = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Duser.home=$passwdHome",
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.MainKt",
            "add", "api-key", "--name", "fw", "--base-url", baseUrl, "--model", "m:1000", "--yes",
        ).directory(tmp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().apply {
            // Only HOME may place a path: every selector that could point one elsewhere is cleared.
            keys.removeIf { key -> SELECTOR_PREFIXES.any(key::startsWith) }
            put("HOME", home.toString())
            put("FW_API_KEY", "k")
            // A port nothing listens on, so the verb finds no daemon to restart.
            put("SPLICE_CONTROL_PORT", TestPorts.reserve().toString())
        }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "splice add ran past $CHILD_SECONDS s")
        } finally {
            process.destroyForcibly()
        }
        return process.exitValue() to Files.readString(output)
    }

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
}

private val SELECTOR_PREFIXES = listOf("SPLICE_", "CLAUDEX_", "XDG_", "CONTROL_")
private const val CHILD_SECONDS = 60L
