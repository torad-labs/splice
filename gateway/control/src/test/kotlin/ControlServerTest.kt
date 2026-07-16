// PORT-OF: server/test/control-server.test.mjs @ 4ca99f7 — bearer guard, /api/status, /api/heads
// + lifecycle, /api/config GET+PATCH (single-JVM: no fanout targets), /api/usage soft-warn
// firing from a seeded 90% ratelimit, /api/auth masked, dashboard serving, 404s. Payload shapes
// match webui/src/shared/api/index.ts (the contract).
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.CompactView
import splice.control.ControlServer
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.LaunchService
import splice.control.LaunchSpec
import splice.control.ManagedHead
import splice.control.RateLimitView
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.nio.file.Files

private class FakeHead(override val key: String, override val port: Int) : Head {
    override val label = key
    var running = true
    override suspend fun start() { running = true }
    override suspend fun stop() { running = false }
    override fun healthSnapshot() = HeadHealth(ok = running, running = running, port = port, version = "kt-1")
}

private class FakeAuth : AuthProvider {
    override suspend fun credentials() = null
    override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", mapOf("account_id_masked" to "acct…5678"))
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlServerTest {

    private lateinit var control: ControlServer
    private lateinit var key: String
    private val port = 39250
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val head = FakeHead("codex", 3099)

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("control-test")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val managed = ManagedHead(
            head = head,
            auth = FakeAuth(),
            usage = object : HeadUsageSource {
                override fun outputTokens5h() = 0L
                override fun entries() = 3
                override fun ratelimit() = RateLimitView(1000, 100, "6m0s") // 90% used -> warn
            },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int) =
                    CompactView(2, mapOf("model_text" to 2), listOf(mapOf("outcome" to "model_text")))
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int) = "[codex] line one\n[codex] line two\n"
            },
            warnPct = 80,
            warnTokens5h = 0,
        )
        val configDir = tmp.resolve(".claude-codex-test")
        val launchSpec = LaunchSpec(
            configDir = configDir,
            pinnedModel = "gpt-5.6-sol",
            availableModelIds = listOf("gpt-5.6-sol"),
            modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
            policy = splice.core.launch.ClaudePolicy(share = emptySet(), isolate = emptySet()),
            port = 3099,
        )
        control = ControlServer(
            port = port,
            heads = mapOf("codex" to managed.copy(launchSpec = launchSpec)),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html><title>splice</title>" },
            log = {},
            launchService = LaunchService(
                splice.core.launch.ClaudeConfigMaterializer(tmp, "\"/bin/curl\" -s :3096/statusline"),
            ),
        )
        control.start()
        Thread.sleep(600)
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    private suspend fun authed(path: String) =
        client.get("http://127.0.0.1:$port$path") { header("Authorization", "Bearer $key") }.bodyAsText()

    @Test
    fun `bearer guard - 401 without the key, 200 with`() = runTest {
        val unauth = client.get("http://127.0.0.1:$port/api/status")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)
        val ok = client.get("http://127.0.0.1:$port/api/status") { header("Authorization", "Bearer $key") }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `dashboard served at root without auth`() = runTest {
        val body = client.get("http://127.0.0.1:$port/").bodyAsText()
        assertTrue(body.contains("splice"))
    }

    @Test
    fun `status lists heads and registry`() = runTest {
        val obj = json.parseToJsonElement(authed("/api/status")).jsonObject
        assertEquals("control", obj["server"]?.jsonPrimitive?.content)
        assertTrue(obj["heads"]!!.jsonArray.any { it.jsonPrimitive.content == "codex" })
        assertEquals("codex", obj["registry"]!!.jsonArray.first().jsonObject["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `heads status carries the webui shape`() = runTest {
        val h = json.parseToJsonElement(authed("/api/heads")).jsonObject["heads"]!!.jsonArray.first().jsonObject
        assertEquals("codex", h["key"]?.jsonPrimitive?.content)
        assertEquals("3099", h["port"]?.jsonPrimitive?.content)
        assertEquals("true", h["running"]?.jsonPrimitive?.content)
        assertTrue(h.containsKey("gate") && h.containsKey("pids") && h.containsKey("healthy"))
    }

    @Test
    fun `head lifecycle - stop then start flips running`() = runTest {
        val stopped = client.post("http://127.0.0.1:$port/api/heads/codex/stop") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertEquals("false", json.parseToJsonElement(stopped).jsonObject["running"]?.jsonPrimitive?.content)
        val started = client.post("http://127.0.0.1:$port/api/heads/codex/start") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertEquals("true", json.parseToJsonElement(started).jsonObject["running"]?.jsonPrimitive?.content)
    }

    @Test
    fun `config exposes effective plus four layers plus restart keys`() = runTest {
        val obj = json.parseToJsonElement(authed("/api/config")).jsonObject
        assertTrue(obj.containsKey("effective"))
        val layers = obj["layers"]!!.jsonObject
        assertTrue(
            layers.containsKey("defaults") && layers.containsKey("file") &&
                layers.containsKey("env") && layers.containsKey("runtime"),
        )
        assertTrue(obj["restart_required_keys"]!!.jsonArray.any { it.jsonPrimitive.content == "port" })
    }

    @Test
    fun `config patch applies without fanout targets`() = runTest {
        val body = client.patch("http://127.0.0.1:$port/api/config") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody("""{"effort":"high","bogus":1}""")
        }.bodyAsText()
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("high", obj["applied"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
        assertTrue(obj["rejected"]!!.jsonObject.containsKey("bogus"))
        assertEquals(0, obj["targets"]!!.jsonArray.size) // single JVM, no fanout
    }

    @Test
    fun `usage soft-warn fires from a 90 percent ratelimit`() = runTest {
        val u = json.parseToJsonElement(authed("/api/usage")).jsonObject["heads"]!!.jsonArray.first().jsonObject
        val warn = u["warn"]!!.jsonObject
        assertEquals("warn", warn["level"]?.jsonPrimitive?.content)
        assertEquals("ratelimit", warn["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `auth is masked, compact summarized, logs tailed`() = runTest {
        val auth = json.parseToJsonElement(authed("/api/auth")).jsonObject["heads"]!!.jsonArray.first().jsonObject
        assertEquals("acct…5678", auth["fields"]!!.jsonObject["account_id_masked"]?.jsonPrimitive?.content)
        val compact = json.parseToJsonElement(authed("/api/compact")).jsonObject["heads"]!!.jsonArray.first().jsonObject
        assertEquals("2", compact["total"]?.jsonPrimitive?.content)
        val logs = json.parseToJsonElement(authed("/api/logs/codex?tail=10")).jsonObject
        assertTrue(logs["log"]?.jsonPrimitive?.content.orEmpty().contains("line one"))
    }

    @Test
    fun `unknown head 404s`() = runTest {
        val r = client.get("http://127.0.0.1:$port/api/logs/nope") { header("Authorization", "Bearer $key") }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `launch returns an exec recipe with head env and argv`() = runTest {
        val body = client.post("http://127.0.0.1:$port/launch/codex") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody("""{"safe":"false","args":["-c"]}""")
        }.bodyAsText()
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("http://127.0.0.1:3099", obj["env"]!!.jsonObject["ANTHROPIC_BASE_URL"]?.jsonPrimitive?.content)
        val argv = obj["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(argv.contains("--dangerously-skip-permissions"))
        assertTrue(argv.contains("--model") && argv.contains("gpt-5.6-sol") && argv.contains("-c"))
    }

    @Test
    fun `statusline renders the model from stdin json, no bearer needed`() = runTest {
        val line = client.post("http://127.0.0.1:$port/statusline/codex") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":{"display_name":"Codex 5.6 Sol"},"current_usage":{"input_tokens":100,"context_window":272000}}""",
            )
        }.bodyAsText()
        assertTrue(line.contains("Codex 5.6 Sol"))
    }
}
