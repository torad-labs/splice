// A claude-openrouter turn reads "API est." on the status line at the price the shipped catalog (or OpenRouter's own
// listing, or a rates line on the row) gives its model; a model with no price reads "no rate card".
//
// The config is what the real `splice add openrouter` writes under a hermetic home, pointed at a fake OpenRouter;
// a real daemon boots from it, one turn goes through the head, and the status line is asked the way Claude Code asks.
package splice.app.statusline

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.configuration.add.AddLinked
import splice.configuration.add.AddLogin
import splice.configuration.add.AddPorts
import splice.configuration.add.AddPrompter
import splice.configuration.add.AddVerb
import splice.configuration.add.DaemonRestart
import splice.configuration.add.DaemonUpProbe
import splice.configuration.add.WrapperInstall
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class OpenRouterStatuslineCostTest {

    private val transcript = mutableListOf<String>()

    @Test
    fun `a turn on a default model reads API est on the status line at the shipped card`(@TempDir tmp: Path) {
        val line = statusLineAfterTurn(tmp)
        // 800,000 fresh input at $2, 200,000 cached at $0.20, 100,000 out at $10 per million: Sonnet 5's card.
        assertTrue("API est. $2.64" in line, line)
        assertFalse("no rate card" in line, line)
        assertFalse("9.99" in line, "the client's own Anthropic-priced figure must not stand in: $line")
    }

    @Test
    fun `a row added by id outside the ten reads API est at the price OpenRouter lists`(@TempDir tmp: Path) {
        val line = statusLineAfterTurn(tmp, model = ADDED, edit = ::listing)
        // The same 800,000 / 200,000 / 100,000 tokens at the listed $2 / $0.20 / $10 per million.
        assertTrue("API est. $2.64" in line, line)
        assertFalse("no rate card" in line, line)
    }

    @Test
    fun `a model OpenRouter lists no price for still reads no rate card`(@TempDir tmp: Path) {
        val line = statusLineAfterTurn(
            tmp,
            model = UNPRICED,
            edit = ::listing,
            expectFigure = false,
        )
        assertTrue("no rate card" in line, line)
        assertFalse("API est." in line, line)
    }

    @Test
    fun `a rates line written on the row wins over the listed price`(@TempDir tmp: Path) {
        val written = """
            [[providers.openrouter.models]]
            id = "$ADDED"
            label = "Added"
            context_window = 200000
            rates = { input = 1.0, cache_read = 0.1, output = 5.0 }
        """.trimIndent()
        val line = statusLineAfterTurn(
            tmp,
            model = ADDED,
            edit = { config -> listing(config).trimEnd('\n') + "\n\n" + written + "\n" },
        )
        // 800,000 at $1, 200,000 at $0.10, 100,000 at $5 per million; the listing's $2.64 is not used.
        assertTrue("API est. $1.32" in line, line)
    }

    /** The fake endpoint is loopback, so local = false asks its model list as for the real provider.
     *  The shipped independent tiers remain intact and no serving allowlist is introduced. */
    private fun listing(config: String): String {
        val header = "[providers.openrouter]\n"
        require(header in config) { "the shipped provider header moved" }
        val head = TopologyLoader.parse(config).heads.getValue("openrouter")
        require(head.models == null && head.modelSlots.isNotEmpty()) { "the shipped tiers must not limit discovery" }
        return config.replaceFirst(header, header + "local = false\n")
    }

    private fun statusLineAfterTurn(
        tmp: Path,
        model: String = MODEL,
        edit: (String) -> String = { it },
        expectFigure: Boolean = true,
    ): String {
        val upstream = FakeOpenRouter()
        val client = HttpClient(CIO)
        try {
            val keyFile = tmp.resolve("or-key.json")
            Files.writeString(keyFile, """{"api_key":"or-e2e-key"}""")
            val controlPort = TestPorts.reserve()
            val headPort = TestPorts.reserve()
            val shipped = shippedConfig(tmp, upstream.baseUrl)
            val config = edit(bootable(shipped, controlPort, headPort, keyFile))
            val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
            val daemon = Daemon(
                topology = TopologyLoader.parse(config),
                statePaths = statePaths,
                log = {},
                refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
            )
            runBlocking { daemon.start() }
            try {
                awaitListening(controlPort, headPort)
                val key = MgmtKey(statePaths).get()
                val turn = Turn(model, awaitFigure = expectFigure)
                return runBlocking { turnThenStatusLine(client, key, controlPort, headPort, turn) }
            } finally {
                runBlocking { daemon.stop() }
            }
        } finally {
            client.close()
            upstream.stop()
        }
    }

    /** What the real `splice add openrouter --yes` writes into splice.toml, against [baseUrl]. */
    private fun shippedConfig(tmp: Path, baseUrl: String): String {
        val path = tmp.resolve("splice.toml")
        val env = EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> path.toString()
                "OPENROUTER_API_KEY" -> "or-e2e-key"
                else -> null
            }
        }
        val sink = TerminalOutput { transcript += it }
        val ports = AddPorts(
            login = AddLogin { _, _, _ -> error("the key is in the environment, so no sign-in runs") },
            install = WrapperInstall { _, _ -> AddLinked.Linked },
            restart = DaemonRestart { true },
            daemonUp = DaemonUpProbe { false },
            prompt = AddPrompter { _, default -> default },
        )
        UserHome.within(tmp) {
            TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
            val args = listOf("openrouter", "--base-url", baseUrl, "--yes")
            val saved = runBlocking { AddVerb(sink, sink, ports).add(args, env) }
            assertTrue(saved, transcript.joinToString("\n"))
        }
        return Files.readString(path)
    }

    /** The shipped config with what a test box cannot take as written: the ports (the everyday
     *  daemon holds 3096) and the key's home. */
    private fun bootable(written: String, controlPort: Int, headPort: Int, keyFile: Path): String {
        val auth = """auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }"""
        require("control_port = 3096" in written && auth in written) { "the shipped config's shape moved:\n$written" }
        return written
            .replace("control_port = 3096", "control_port = $controlPort")
            .replace(auth, """auth = { kind = "api-key", file = "${keyFile.toString().replace('\\', '/')}" }""")
            .replace(Regex("(?m)^port = \\d+$"), "port = $headPort")
    }

    private suspend fun turnThenStatusLine(
        client: HttpClient,
        key: String,
        controlPort: Int,
        headPort: Int,
        turn: Turn,
    ): String {
        val sse = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"${turn.model}","stream":true,"max_tokens":100,""" +
                    """"messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
        assertTrue("event: message_stop" in sse, sse)
        var line = statusLine(client, key, controlPort, turn.model)
        // The perf row lands just after the stream closes; a priced model reads nothing until it does.
        if (turn.awaitFigure) {
            val deadline = System.nanoTime() + SETTLE_NANOS
            while ("API est." !in line && System.nanoTime() < deadline) {
                delay(POLL_MS)
                line = statusLine(client, key, controlPort, turn.model)
            }
        }
        return line
    }

    /** The line Claude Code's status-line hook gets back for this session, colour codes stripped. The blob
     *  carries the client's own Anthropic-priced total, which a non-Anthropic head must never show. */
    private suspend fun statusLine(client: HttpClient, key: String, controlPort: Int, model: String): String =
        client.post("http://127.0.0.1:$controlPort/statusline/openrouter") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            setBody(
                """{"session_id":"$SESSION","model":{"id":"$model","display_name":"Claude Sonnet 5"},""" +
                    """"cost":{"total_cost_usd":9.99}}""",
            )
        }.bodyAsText().replace(ANSI, "")
}

private const val SESSION = "v4434e2e-0000-4000-8000-000000000001"
private const val MODEL = "anthropic/claude-sonnet-5"

/** The model a turn runs on, and whether its status line settles on a figure ([awaitFigure]) or on none. */
private data class Turn(val model: String, val awaitFigure: Boolean)

/** Two models outside the ten, as OpenRouter lists them: one with a card, one whose price depends
 *  on the route it picks, which it lists as -1. */
private const val ADDED = "acme/added-model"
private const val UNPRICED = "acme/router-model"
private const val POLL_MS = 200L
private const val SETTLE_NANOS = 10_000_000_000L
private val ANSI = Regex("\u001b\\[[0-9;]*m")

/** The ten models `splice add openrouter` ships, as the fake endpoint lists them: the add verb refuses a
 *  row the endpoint does not list, so a drift between this list and the profile fails the run by name. */
private val LISTED = listOf(
    "anthropic/claude-sonnet-5",
    "anthropic/claude-opus-5.5",
    "z-ai/glm-5.3-flash",
    "openai/gpt-6-sol",
    "openai/gpt-6-luna",
    "google/gemini-3.8-flash",
    "deepseek/deepseek-v4.1-flash",
    "z-ai/glm-5.3",
    "meta-llama/llama-4-maverick",
    "anthropic/claude-haiku-4.5",
)

/** The two models with the `pricing` block OpenRouter puts on every row of GET /models: per TOKEN, as
 *  decimal strings. The first is priced at Sonnet 5's card, so the figure is the same 2.64. */
private val PRICED_ROWS = listOf(
    """{"id":"$ADDED","pricing":{"prompt":"0.000002","completion":"0.00001","input_cache_read":"0.0000002"}}""",
    """{"id":"$UNPRICED","pricing":{"prompt":"-1","completion":"-1"}}""",
)

/** OpenRouter's two routes the run reaches: GET /models, and a chat completion that reports one turn's
 *  usage, 1,000,000 prompt tokens of which 200,000 were cached, and 100,000 completion tokens. */
private class FakeOpenRouter {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}/api/v1"

    init {
        server.executor = pool
        server.createContext("/api/v1") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val _ = ex.requestBody.readBytes()
        if (path.endsWith("/chat/completions")) {
            chat(ex)
        } else {
            val body = if (path.endsWith("/models")) {
                val rows = LISTED.map { """{"id":"$it"}""" } + PRICED_ROWS
                rows.joinToString(",", """{"data":[""", "]}")
            } else {
                "{}"
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
        }
    }

    private fun chat(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        val frames = listOf(
            """{"choices":[{"delta":{"content":"hi"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1000000,""" +
                """"completion_tokens":100000,"prompt_tokens_details":{"cached_tokens":200000}}}""",
        )
        frames.forEach { ex.responseBody.write("data: $it\n\n".toByteArray()) }
        ex.responseBody.write("data: [DONE]\n\n".toByteArray())
        Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
        Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
    }
}
