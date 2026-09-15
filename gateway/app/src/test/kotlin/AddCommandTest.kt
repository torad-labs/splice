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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private const val TOKENS = """{"tokens":{"access_token":"a","refresh_token":"r"}}"""
private const val EDIT = "\n# edited meanwhile\n"

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

    private fun command(
        http: AddHttp,
        login: Boolean = true,
        daemonUp: Boolean = false,
        restartOk: Boolean = true,
        editConfigDuringLogin: Boolean = false,
        deleteConfigDuringLogin: Boolean = false,
        answers: Map<String, ArrayDeque<String>> = emptyMap(),
    ) = AddCommand(
        checks = AddChecks(http),
        login = { key, provider, _ ->
            if (login) {
                Files.writeString(authFile(provider.auth.kind), TOKENS)
                installed += "login:$key"
            }
            if (editConfigDuringLogin) Files.writeString(config(), Files.readString(config()) + EDIT)
            if (deleteConfigDuringLogin) Files.delete(config())
            login
        },
        install = { key, _ ->
            installed += key
            true
        },
        restart = {
            restarted += 1
            restartOk
        },
        daemonUp = { daemonUp },
        prompt = { question, default -> answers[question]?.removeFirstOrNull()?.ifEmpty { default } ?: default },
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
    fun `a mistyped flag, a flag without its value or a second word is refused, not swallowed`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val cmd = command(http(fwRoutes))
        val good = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--model", "m", "--yes")
        listOf(
            good + "--nam" + "x",
            good + "--modle" + "m",
            good + "extra",
            good + "--model",
            good + "--model" + "--live",
            listOf("-y", "api-key"),
        ).forEach { args -> assertFalse(runBlocking { cmd.add(args, env) }, args.toString()) }
        assertEquals(before, Files.readString(config()), "nothing written for a refused command line")
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

    @Test
    fun `a config edited while the sign-in ran is not overwritten, and a failed restart is not a success`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
        val racing = command(http(routes), daemonUp = true, editConfigDuringLogin = true)
        assertFalse(runBlocking { racing.add(listOf("codex", "--yes"), env) }, "the write is refused")
        assertEquals(before + EDIT, Files.readString(config()), "the edit survives, not the add")
        Files.writeString(config(), before)
        val failing = command(http(routes), daemonUp = true, restartOk = false)
        assertFalse(runBlocking { failing.add(listOf("codex", "--yes"), env) }, "asked-for restart failed")
        assertTrue(Files.readString(config()).contains("[heads.codex]"), "but the head is saved for splice restart")
    }

    @Test
    fun `a config deleted while the sign-in ran is refused, not recreated from the stale candidate`(
        @TempDir home: Path,
    ) = withHome(home) {
        starter()
        val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
        val racing = command(http(routes), daemonUp = true, deleteConfigDuringLogin = true)
        val out = capture { assertFalse(runBlocking { racing.add(listOf("codex", "--yes"), env) }) }
        assertTrue(out.contains("could not be read again"), out)
        assertFalse(Files.exists(config()), "a rename must not recreate the file from the stale candidate")
        assertEquals(0, restarted)
    }

    @Test
    fun `a typed context window that is not a positive integer is asked again, three misses refuse the add`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val args = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--yes")
        val retried = command(
            http(fwRoutes),
            answers = mapOf(
                "model id (blank when done):" to ArrayDeque(listOf("m", "")),
                "context window for m:" to ArrayDeque(listOf("32k", "99999999999999999999", "64000")),
            ),
        )
        assertTrue(runBlocking { retried.add(args, env) })
        val window = TopologyLoader.parse(Files.readString(config())).providers.getValue("fw").models.single()
        assertEquals(64_000L, window.contextWindow, "the third answer, never the 128000 default")
        Files.writeString(config(), before)
        val refused = command(
            http(fwRoutes),
            answers = mapOf(
                "model id (blank when done):" to ArrayDeque(listOf("m", "")),
                "context window for m:" to ArrayDeque(listOf("32k", "1.5", "-3", "64000")),
            ),
        )
        val out = capture { assertFalse(runBlocking { refused.add(args, env) }) }
        assertTrue(out.contains("must be a positive integer"), out)
        assertTrue(
            out.contains("splice add: context window for m must be a positive integer (tokens)"),
            out,
        )
        assertTrue(!out.contains("withheld"), out)
        assertEquals(before, Files.readString(config()), "nothing written")
    }

    @Test
    fun `a model id may carry colons and the window is the last segment, a non-positive window is refused`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val routes = fwRoutes + ("GET http://localhost:1/v1/models" to """{"data":[{"id":"qwen3:4b"},{"id":"m"}]}""")
        val base = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--yes")
        assertFalse(runBlocking { command(http(routes)).add(base + "--model" + "m:0", env) })
        assertFalse(runBlocking { command(http(routes)).add(base + "--model" + "m:-1", env) })
        assertEquals(before, Files.readString(config()), "nothing written for a non-positive window")
        val twice = base + "--model" + "qwen3:4b:32768" + "--model" + "qwen3:4b"
        assertFalse(runBlocking { command(http(routes)).add(twice, env) }, "one id, two windows: refused")
        assertEquals(before, Files.readString(config()), "nothing written for a repeated model id")
        val two = base + "--model" + "qwen3:4b:32768" + "--model" + "m"
        assertTrue(runBlocking { command(http(routes)).add(two, env) })
        val models = TopologyLoader.parse(Files.readString(config())).providers.getValue("fw").models
        assertEquals(listOf("qwen3:4b" to 32_768L, "m" to 128_000L), models.map { it.id to it.contextWindow })
    }

    @Test
    fun `a config without a trailing newline, or without any head, is still added to`(@TempDir home: Path) =
        withHome(home) {
            val trimmed = starter().trimEnd('\n')
            Files.writeString(config(), trimmed)
            val base = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1", "--model", "m", "--yes")
            assertTrue(runBlocking { command(http(fwRoutes)).add(base, env) }, "one newline is not a change")
            assertTrue(Files.readString(config()).startsWith(trimmed + "\n"), "the file is appended, not rewritten")
            Files.writeString(config(), "")
            assertTrue(runBlocking { command(http(fwRoutes)).add(base, env) }, "no head to take a port from")
            assertTrue(Files.readString(config()).contains("[heads.fw]"))
        }

    @Test
    fun `a model list the endpoint cannot serve refuses the add instead of trusting the rows`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val noList = fwRoutes.filterKeys { !it.endsWith("/models") }
        val args = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1") +
            listOf("--model", "m:1000", "--yes")
        assertFalse(runBlocking { command(http(noList)).add(args, env) })
        assertEquals(before, Files.readString(config()))
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `an oauth file without token material still requires a sign-in`(@TempDir home: Path) = withHome(home) {
        val before = starter()
        Files.writeString(authFile("chatgpt-oauth"), "{}")
        val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("codex", "--yes"), env) })
        assertEquals(before, Files.readString(config()))
        assertTrue(runBlocking { command(http(routes)).add(listOf("codex", "--yes"), env) })
        assertEquals(listOf("login:codex", "codex"), installed, "the gutted file did not stand in for a sign-in")
    }

    @Test
    fun `--live on a browser-oauth profile is refused before anything is asked`(@TempDir home: Path) =
        withHome(home) {
            val before = starter()
            val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
            assertFalse(runBlocking { command(http(routes)).add(listOf("codex", "--live", "--yes"), env) })
            assertEquals(before, Files.readString(config()))
            assertTrue(installed.isEmpty(), "no sign-in ran for a refused flag")
        }

    @Test
    fun `a kimi file with only an access token requires a sign-in, one with a refresh token is accepted`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val routes = mapOf("GET https://api.kimi.com/coding" to "{}")
        Files.writeString(authFile("kimi-oauth"), """{"access_token":"dead"}""")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("kimi", "--yes"), env) })
        assertEquals(before, Files.readString(config()))
        Files.writeString(authFile("kimi-oauth"), """{"access_token":"a","refresh_token":"r","expires_at":1}""")
        assertTrue(runBlocking { command(http(routes), login = false).add(listOf("kimi", "--yes"), env) })
        val kimi = TopologyLoader.parse(Files.readString(config())).providers.getValue("kimi")
        assertEquals("kimi-oauth", kimi.auth.kind)
        assertEquals(listOf("kimi"), installed, "no sign-in ran: the flat kimi file is refreshable")
    }

    @Test
    fun `a muse file with an account token is accepted even without a minted key`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val routes = mapOf("GET https://api.meta.ai" to "{}")
        Files.writeString(authFile("muse-oauth"), "{}")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("muse", "--yes"), env) })
        assertEquals(before, Files.readString(config()))
        Files.writeString(authFile("muse-oauth"), """{"access_token":"acct-token-fake"}""")
        assertTrue(runBlocking { command(http(routes), login = false).add(listOf("muse", "--yes"), env) })
        val topology = TopologyLoader.parse(Files.readString(config()))
        assertEquals("muse-oauth", topology.providers.getValue("muse").auth.kind)
        assertEquals("https://api.meta.ai", topology.providers.getValue("muse").baseUrl)
        assertEquals("claude-muse", topology.heads.getValue("muse").claude.command)
        assertEquals(listOf("muse"), installed, "no sign-in ran: the account token is enough")
    }

    @Test
    fun `a chatgpt file with token fields under a decoy object and an empty tokens object requires a sign-in`(
        @TempDir home: Path,
    ) = withHome(home) {
        val before = starter()
        val decoy = """{"metadata":{"access_token":"a","refresh_token":"r"},"tokens":{}}"""
        Files.writeString(authFile("chatgpt-oauth"), decoy)
        val routes = mapOf("GET https://chatgpt.com/backend-api/codex" to "{}")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("codex", "--yes"), env) })
        assertEquals(before, Files.readString(config()))
    }

    @Test
    fun `a grok file with an expired token and no refresh requires a sign-in, a future expiry is accepted`(
        @TempDir home: Path,
    ) = withHome(home) {
        starter()
        val routes = mapOf("GET https://api.x.ai/v1" to "{}")
        Files.writeString(authFile("grok-oauth"), """{"tokens":{"access_token":"a"},"expires":1}""")
        assertFalse(runBlocking { command(http(routes), login = false).add(listOf("grok", "--yes"), env) })
        val ahead = System.currentTimeMillis() + HOUR_MS
        Files.writeString(authFile("grok-oauth"), """{"tokens":{"access_token":"a"},"expires":$ahead}""")
        assertTrue(runBlocking { command(http(routes), login = false).add(listOf("grok", "--yes"), env) })
        assertEquals(listOf("grok"), installed)
    }

    @Test
    fun `the claude profile lands with no credential of its own`(@TempDir home: Path) = withHome(home) {
        starter()
        val routes = mapOf("GET https://api.anthropic.com" to "{}")
        assertTrue(runBlocking { command(http(routes), login = false).add(listOf("claude", "--yes"), env) })
        val topology = TopologyLoader.parse(Files.readString(config()))
        assertEquals("client", topology.providers.getValue("claude-splice").auth.kind)
        assertEquals("claude-splice", topology.heads.getValue("claude-splice").claude.command)
        assertEquals(listOf("claude-splice"), installed, "no sign-in, the wrapper linked")
    }

    @Test
    fun `a command equal to a head whose command is omitted is refused`(@TempDir home: Path) = withHome(home) {
        val implicit = starter().replace("command = \"claude-openrouter\"\n", "")
        Files.writeString(config(), implicit)
        val collide = listOf("api-key", "--name", "fw", "--base-url", "http://localhost:1/v1") +
            listOf("--model", "m:1000", "--command", "openrouter", "--yes")
        assertFalse(runBlocking { command(http(fwRoutes)).add(collide, env) })
        assertEquals(implicit, Files.readString(config()))
    }

    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buf, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buf.toString()
    }
}
