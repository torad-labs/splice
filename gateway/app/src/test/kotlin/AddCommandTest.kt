// `splice add` (v0.4.0, FEATURES.md §1) against a fake HOME, no network, no terminal: a second
// provider lands next to the starter without editing TOML; every refusal and every failed check
// leaves the previous file byte-identical.
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.AddChecks
import splice.app.cli.AddCommand
import splice.app.cli.AddHttp
import splice.app.cli.AddHttpReply
import splice.core.topology.AuthKindRegistry
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class AddCommandTest {

    private val env = EnvReader { name -> if (name == "FW_API_KEY") "k" else null }
    private val installed = mutableListOf<String>()
    private var restarted = 0

    private fun withHome(home: Path, block: () -> Unit) {
        val prev = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            block()
        } finally {
            System.setProperty("user.home", prev)
        }
    }

    private fun http(routes: Map<String, String>) = AddHttp { method, url, _, _ ->
        routes["$method $url"]?.let { AddHttpReply(200, it) }
    }

    private fun command(http: AddHttp, login: Boolean = true, daemonUp: Boolean = false) = AddCommand(
        checks = AddChecks(http),
        login = { key, _, _ ->
            if (login) {
                Files.writeString(authFile("chatgpt-oauth"), "{}")
                installed += "login:$key"
            }
            login
        },
        install = { key, _ ->
            installed += key
            true
        },
        restart = {
            restarted += 1
            true
        },
        daemonUp = { daemonUp },
        prompt = { _, default -> default },
    )

    private fun authFile(kind: String): Path {
        val file = Path.of(TopologyLoader.expandHome(checkNotNull(AuthKindRegistry.defaultAuthFileFor(kind))))
        Files.createDirectories(file.parent)
        return file
    }

    private fun config(): Path = TopologyLoader.configPath(env)

    private fun starter(): String {
        TopologyLoader.loadOrMaterialize(config())
        return Files.readString(config())
    }

    private val fwRoutes = mapOf(
        "GET http://localhost:1/v1" to "{}",
        "GET http://localhost:1/v1/models" to """{"data":[{"id":"m"},{"id":"other"}]}""",
        "POST http://localhost:1/v1/chat/completions" to """{"choices":[{"message":{"content":"pong"}}]}""",
    )

    @Test
    fun `an api-key endpoint lands next to the starter, the starter intact`(@TempDir home: Path) = withHome(home) {
        val before = starter()
        val args = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--model", "m:1000")
        val ok = runBlocking { command(http(fwRoutes)).add(args + listOf("--live", "--yes"), env) }
        assertTrue(ok)
        val text = Files.readString(config())
        assertTrue(text.startsWith(before.trimEnd('\n')), "the previous file is a prefix of the new one")
        val topology = TopologyLoader.parse(text)
        assertEquals(setOf("openrouter", "fw"), topology.heads.keys)
        assertEquals("claude-fw", topology.heads.getValue("fw").claude.command)
        assertEquals("FW_API_KEY", topology.providers.getValue("fw").auth.env)
        assertEquals(1000L, topology.providers.getValue("fw").models.single().contextWindow)
        assertEquals(topology.heads.getValue("openrouter").port + 1, topology.heads.getValue("fw").port)
        assertEquals(listOf("fw"), installed)
        assertEquals(0, restarted, "no daemon was up, nothing to restart")
    }

    @Test
    fun `an unreachable endpoint or an unlisted model writes nothing`(@TempDir home: Path) = withHome(home) {
        val before = starter()
        val base = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--yes")
        val unreachable = runBlocking { command(http(emptyMap())).add(base + listOf("--model", "m:1000"), env) }
        assertFalse(unreachable)
        val unlisted = runBlocking { command(http(fwRoutes)).add(base + listOf("--model", "nope:1000"), env) }
        assertFalse(unlisted)
        assertEquals(before, Files.readString(config()))
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `a taken key, a missing base url and an unknown profile are refused before anything is asked`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val cmd = command(http(fwRoutes))
        val taken = listOf("api-key", "--name", "openrouter", "--base-url", "http://x", "--model", "m")
        assertFalse(runBlocking { cmd.add(taken, env) })
        assertFalse(runBlocking { cmd.add(listOf("api-key", "--name", "fw", "--model", "m"), env) })
        assertFalse(runBlocking { cmd.add(listOf("api-key", "--base-url", "http://x", "--model", "m"), env) })
        assertFalse(runBlocking { cmd.add(listOf("nope"), env) })
        assertEquals(before, Files.readString(config()))
    }

    @Test
    fun `an oauth profile signs in and lands its provider and head, and a refused sign-in writes nothing`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("codex", "--yes"), env) })
        assertEquals(before, Files.readString(config()))
        assertTrue(runBlocking { command(http(routes), daemonUp = true).add(listOf("codex", "--yes"), env) })
        val topology = TopologyLoader.parse(Files.readString(config()))
        assertEquals("chatgpt-oauth", topology.providers.getValue("codex").auth.kind)
        assertEquals("claudex", topology.heads.getValue("codex").claude.command)
        assertEquals("gpt-5.6-sol", topology.heads.getValue("codex").pinnedModel)
        assertEquals(listOf("login:codex", "codex"), installed)
        assertEquals(1, restarted, "the daemon was up and --yes accepted the restart")
    }
}
