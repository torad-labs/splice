// `splice add` run the way a sandbox runs it: a JVM whose HOME names one directory while its
// user.home (the passwd entry's home) names another. Every file the verb writes belongs under HOME. A path read
// from user.home instead would write its splice.toml into the other directory: a sandbox,
// CI or second-profile run wrote into the real home's config. A child JVM because HOME is the process's
// environment, which no in-process test can give a different value.
//
// The same child-JVM entry also proves the section 1 acceptance of the v0.4.0 spec: a second provider is added with
// no TOML editing and the first head is untouched, and an add that fails leaves the file byte for byte as it was.
package splice.app.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.streams.toList

class AddUnderHomeTest {

    @Test
    fun `splice add writes under HOME and nothing under user home`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val passwdHome = Files.createDirectories(tmp.resolve("passwd-home"))
        val server = servingModels()
        val (exit, output) = try {
            add(tmp, home, passwdHome, "fw", "http://127.0.0.1:${server.address.port}/v1")
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
    fun `a second provider is added without editing TOML and the first head is untouched`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val passwdHome = Files.createDirectories(tmp.resolve("passwd-home"))
        val server = servingModels()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val (firstExit, firstOut) = add(tmp, home, passwdHome, "fw", url)
            assertEquals(0, firstExit, firstOut)
            val config = home.resolve(".config/splice/splice.toml")
            val afterFirst = Files.readString(config)

            val (secondExit, secondOut) = add(tmp, home, passwdHome, "second", url)

            assertEquals(0, secondExit, secondOut)
            val afterSecond = Files.readString(config)
            assertTrue(afterSecond.startsWith(afterFirst), "the first head's text changed:\n$afterSecond")
            val heads = TopologyLoader.parse(afterSecond).heads
            assertEquals(setOf("fw", "second"), heads.keys, "both heads are in the saved topology")
            assertEquals(TopologyLoader.parse(afterFirst).heads.getValue("fw"), heads.getValue("fw"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an add that fails leaves the saved topology byte for byte as it was`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val passwdHome = Files.createDirectories(tmp.resolve("passwd-home"))
        val server = servingModels()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            assertEquals(0, add(tmp, home, passwdHome, "fw", url).first)
            val config = home.resolve(".config/splice/splice.toml")
            val before = Files.readString(config)
            // A port nothing listens on: the reachability check refuses before anything is written.
            val dead = "http://127.0.0.1:${TestPorts.reserve()}/v1"

            val (exit, output) = add(tmp, home, passwdHome, "broken", dead)

            assertTrue(exit != 0, "a failed add exits non-zero:\n$output")
            assertEquals(before, Files.readString(config), "the failed add changed the saved topology:\n$output")
            assertEquals(setOf("fw"), TopologyLoader.parse(before).heads.keys)
        } finally {
            server.stop(0)
        }
    }

    private fun add(tmp: Path, home: Path, passwdHome: Path, name: String, baseUrl: String): Pair<Int, String> {
        val output = tmp.resolve("add-$name.out")
        val builder = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Duser.home=$passwdHome",
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.MainKt",
            "add", "api-key", "--name", name, "--base-url", baseUrl, "--model", "m:1000", "--yes",
        ).directory(tmp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().apply {
            // Only HOME may place a path: every selector that could point one elsewhere is cleared.
            keys.removeIf { key -> SELECTOR_PREFIXES.any(key::startsWith) }
            put("HOME", home.toString())
            put("${name.uppercase()}_API_KEY", "k")
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
