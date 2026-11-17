// A laptop taking its commands from a team's server (docs/specs/server-mode.md, F7), its verbs run as a person runs
// them: `splice login <server> --key-stdin` keeps an operator-issued key only once the server accepts it, and only
// where a sign-in keeps its own; `splice remote add <server>` links every command the server's profile lists;
// `splice remote refresh <server>` follows the server as it adds and drops commands; and `splice remote set <command>
// --window` pins a window on the laptop or gives the server's back. The server is a stand-in answering its profile to
// one key; the laptop's daemon is down, so a verb that would restart it says it follows the file at its next start.
package splice.app.cli.share

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.server.ServerLogin
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.topology.RemoteConfig
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText

private const val KEY = "spk_team0001_abcdefghijklmnopqrstuvwxyzABCDEF0123"

class ServerRemoteCliTest {
    @TempDir lateinit var dir: Path

    /** The commands the server offers now; a test changes them as an operator changes `[server] commands`. */
    @Volatile private var offered = listOf("claude-team", "claude-two")

    private val box = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/server/profile") { exchange ->
            val accepted = exchange.requestHeaders.getFirst("authorization") == "Bearer $KEY"
            val commands = offered.joinToString(",") { """{"command":"$it","models":[]}""" }
            val body = if (accepted) {
                """{"key":{"person":"ann@team.example","prefix":"spk_team0001","expires_at":null},""" +
                    """"commands":[$commands],"signin":null,"telemetry":null,"client":{"minimum":null}}"""
            } else {
                """{"type":"error","error":{"type":"authentication_error","message":"splice server: not a key here"}}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (accepted) 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val server = "http://127.0.0.1:${box.address.port}"

    private val env by lazy {
        val vars = mapOf(
            "SPLICE_CONFIG" to dir.resolve("splice.toml").toString(),
            "SPLICE_BIN_DIR" to dir.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to dir.resolve("share").toString(),
            "SPLICE_STATE_DIR" to dir.resolve("state").toString(),
            "HOME" to dir.toString(),
            "PATH" to dir.resolve("bin").toString(),
        )
        EnvReader { vars[it] }
    }

    private val config get() = dir.resolve("splice.toml")
    private val remotes: Map<String, RemoteConfig> get() = TopologyLoader.parse(config.readText()).remote

    /** A laptop with a command of its own, `plain`, and splice's launch shim, whose daemon is down. */
    private fun laptop() {
        Files.createDirectories(dir.resolve("share"))
        Files.writeString(dir.resolve("share/splice-launch"), "#!/bin/sh\n")
        val toml = """
            [daemon]
            control_port = ${TestPorts.reserve()}

            [providers.anthropic]
            dialect = "anthropic-passthrough"
            base_url = "https://api.anthropic.com"
            auth = { kind = "client" }

            [heads.plain]
            provider = "anthropic"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-plain--"
        """.trimIndent()
        Files.writeString(config, toml + "\n")
    }

    private fun linked(command: String): Boolean = dir.resolve("bin").resolve(command).isSymbolicLink()

    private fun login(key: String): Said = captured { out, err ->
        runBlocking { ServerLogin(out, err, env).keep(server, key) }
    }

    private fun remote(vararg args: String): Said = captured { out, err ->
        runBlocking { RemoteCommand(out, err, env).run(args.toList()) }
    }

    private fun captured(run: (TerminalOutput, TerminalOutput) -> Int): Said {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = run(TerminalOutput { out.appendLine(it) }, TerminalOutput { err.appendLine(it) })
        return Said(code, out.toString(), err.toString())
    }

    /** Every file under the laptop's folder that holds [text]. */
    private fun holding(text: String): List<Path> = Files.walk(dir).use { files ->
        files.filter { Files.isRegularFile(it) && text in Files.readString(it) }.toList()
    }

    @AfterEach
    fun stop() = box.stop(0)

    @Test
    fun `a key the server refuses is kept nowhere, and one it accepts only beside the sign-ins, naming whose`() {
        laptop()
        val wrong = "spk_00000000_notakeynotakeynotakeynotakeynotakey"
        val refused = login(wrong)
        assertNotEquals(0, refused.code, refused.toString())
        assertTrue("not a key here" in refused.err, refused.toString())
        assertEquals(emptyList<Path>(), holding("notakeynotakey"))

        val kept = login(KEY)
        assertEquals(0, kept.code, kept.toString())
        assertTrue("ann@team.example" in kept.out, kept.toString())
        val files = holding(KEY)
        assertEquals(1, files.size, files.toString())
        assertTrue(files.single().startsWith(dir.resolve("state/server-logins")), files.toString())
    }

    @Test
    fun `remote add with the server alone links every command its profile lists, each at its own address`() {
        laptop()
        login(KEY)
        val added = remote("add", server)
        assertEquals(0, added.code, added.toString())
        assertEquals(offered.associateWith { RemoteConfig("$server/$it") }, remotes)
        offered.forEach { assertTrue(linked(it), "$it is not linked\n$added") }
        assertFalse(KEY in config.readText(), "splice.toml holds the key")
    }

    @Test
    fun `refresh follows what the server adds and drops, and leaves what the person took out out`() {
        laptop()
        login(KEY)
        remote("add", server)
        remote("remove", "claude-two")
        offered = listOf("claude-two", "claude-three")

        val refreshed = remote("refresh", server)
        assertEquals(0, refreshed.code, refreshed.toString())
        assertEquals(mapOf("claude-three" to RemoteConfig("$server/claude-three")), remotes)
        assertTrue(linked("claude-three"), refreshed.toString())
        assertFalse(linked("claude-team"), refreshed.toString())
        assertTrue("claude-three" in refreshed.out && "claude-team" in refreshed.out, refreshed.toString())
        assertTrue("plain" in TopologyLoader.parse(config.readText()).heads, "the laptop's own command went")

        val before = config.readText()
        val again = remote("refresh", server)
        assertEquals(0, again.code, again.toString())
        assertTrue("nothing changed" in again.out, again.toString())
        assertEquals(before, config.readText())
    }

    @Test
    fun `refresh without a key for the server says how to keep one`() {
        laptop()
        val refused = remote("refresh", server)
        assertNotEquals(0, refused.code, refused.toString())
        assertTrue("splice login $server" in refused.err, refused.toString())
    }

    @Test
    fun `set pins a window that the list shows and server gives it back, refusing a bad window or command`() {
        laptop()
        login(KEY)
        remote("add", server)

        val pinned = remote("set", "claude-team", "--window", "64000")
        assertEquals(0, pinned.code, pinned.toString())
        assertEquals(64_000L, remotes.getValue("claude-team").window)
        assertTrue("64000" in remote().out)

        val before = config.readText()
        for (args in listOf(listOf("claude-team", "lots"), listOf("claude-team", "0"), listOf("plain", "64000"))) {
            val refused = remote("set", args[0], "--window", args[1])
            assertNotEquals(0, refused.code, "$args\n$refused")
        }
        assertEquals(before, config.readText())

        val back = remote("set", "claude-team", "--window", "server")
        assertEquals(0, back.code, back.toString())
        assertNull(remotes.getValue("claude-team").window)
    }

    @Test
    fun `adding the server again keeps a window the person pinned`() {
        laptop()
        login(KEY)
        remote("add", server)
        remote("set", "claude-team", "--window", "64000")
        assertEquals(0, remote("add", server).code)
        assertEquals(64_000L, remotes.getValue("claude-team").window)
    }
}

/** One run of a verb: its exit code, and what it printed on stdout and stderr. */
private data class Said(val code: Int, val out: String, val err: String) {
    override fun toString(): String = "exit $code\n--- out\n$out\n--- err\n$err"
}
