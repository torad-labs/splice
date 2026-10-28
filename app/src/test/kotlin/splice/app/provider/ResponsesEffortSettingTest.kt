// Marcos, Oct 28: "summary detailed always by default, effort at high by default, but these should be configurable
// by users in the console and in the toml file." splice authors a Responses head's reasoning effort: a head with no
// setting sends high whatever effort Claude Code picked, and a setting in splice.toml (or the console, which writes
// the same layers) is what the head sends. Summary is detailed unless a setting says otherwise.
//
// These build the hand-written API-key OpenAI Responses head from splice.toml the way the daemon builds it, its knobs
// read through the same layers (TopologyKnobLayer for [defaults], [heads.<key>.overrides] per head), point it at a
// scripted backend, and send it a request the way Claude Code does, with Claude Code's own effort picked at max.
package splice.app.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.scripted.ScriptedProvider
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.topology.TopologyLoader
import splice.upstream.Provider
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "synthetic-openai"

class ResponsesEffortSettingTest {

    @Test
    fun `a Responses head with no setting sends high effort and a detailed summary whatever Claude Code picked`(
        @TempDir root: Path,
    ) = runBlocking {
        val reasoning = EffortRig.reasoningSent(root, "")
        assertEquals("high", reasoning?.str("effort"), "splice's default effort is high, not Claude Code's max")
        assertEquals("detailed", reasoning?.str("summary"), "splice's default summary is detailed")
    }

    @Test
    fun `a Responses head sends the effort its own overrides set, over Claude Code's pick`(@TempDir root: Path) =
        runBlocking {
            val reasoning = EffortRig.reasoningSent(root, "[heads.$HEAD.overrides]\neffort = \"medium\"")
            assertEquals("medium", reasoning?.str("effort"), "the head's own [heads.$HEAD.overrides] effort rides")
        }

    @Test
    fun `a Responses head sends the effort and summary splice toml's defaults set`(@TempDir root: Path) =
        runBlocking {
            val reasoning = EffortRig.reasoningSent(root, "[defaults]\neffort = \"low\"\nsummary = \"concise\"")
            assertEquals("low", reasoning?.str("effort"), "[defaults] effort reaches a head with none of its own")
            assertEquals("concise", reasoning?.str("summary"), "[defaults] summary reaches every head")
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
}

/** One API-key OpenAI Responses head built from splice.toml as the daemon builds it, over a scripted backend. */
private object EffortRig {
    /** The reasoning block the head's one request carried, with [settings] written into its splice.toml. */
    suspend fun reasoningSent(root: Path, settings: String): JsonObject? {
        Files.createDirectories(root)
        val backend = ScriptedProvider(VirtualTime())
        backend.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val head = HeadServer(
            provider = provider(root, scope, topology(root, backend, settings)),
            listenPort = 0,
            deps = headDeps(tmp = root, upstream = UpstreamClient(totalTimeoutMs = 30_000L))
                .copy(policy = HeadDeps.HeadPolicy(stopDrainMs = 0)),
        )
        try {
            head.start()
            awaitListening(head.port)
            backend.script(reply { responsesAnswer("synthetic effort answer") })
            assertTrue(ask(head.port).contains("synthetic effort answer"), "the head answered")
            val body = Json.parseToJsonElement(backend.requests.single().body).jsonObject
            return body["reasoning"] as? JsonObject
        } finally {
            head.stop()
            backend.stop()
            scope.cancel()
        }
    }

    /** splice.toml holding one API-key OpenAI Responses head over [backend], with [settings] written after it. */
    private fun topology(root: Path, backend: ScriptedProvider, settings: String): Topology {
        val keyFile = Files.writeString(root.resolve("key"), "synthetic-file-key")
        return TopologyLoader.parse(
            """
            |[providers.$HEAD]
            |dialect = "openai-responses"
            |base_url = "${backend.baseUrl}/v1"
            |auth = { kind = "api-key", file = "${keyFile.toString().replace("\\", "/")}" }
            |[[providers.$HEAD.models]]
            |id = "gpt-synthetic"
            |context_window = 200000
            |[heads.$HEAD]
            |provider = "$HEAD"
            |port = 31399
            |discovery_prefix = "claude-synthetic--"
            |pinned_model = "gpt-synthetic"
            |$settings
            """.trimMargin(),
        )
    }

    /** The head's provider, built as the daemon builds it, its knobs read through the daemon's layers. */
    private fun provider(root: Path, scope: CoroutineScope, topology: Topology): Provider {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val config = ConfigService(
            paths,
            headOverrides = TopologyKnobLayer(topology).configOverrides(),
            perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
            envReader = { null },
        )
        val headCfg = topology.heads.getValue(HEAD)
        val providerCfg = topology.providers.getValue(headCfg.provider)
        return ProviderAssembly(
            paths,
            scope,
            log = LogSink { },
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        ).buildProvider(
            ProviderBuild(
                key = HEAD,
                head = headCfg,
                providerCfg = providerCfg,
                catalog = providerCfg.catalogFor(headCfg),
                faultPlan = UpstreamFaultPlan(
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "$HEAD login",
                ),
                cfg = config.getConfig(HEAD),
            ),
        ).provider
    }

    /** One request to the head on [port], as Claude Code sends it with its own effort picked at max, and the answer. */
    private suspend fun ask(port: Int): String {
        val client = HttpClient(CIO)
        try {
            return client.post("http://127.0.0.1:$port/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", "synthetic-effort-session")
                setBody(
                    """{"model":"claude-synthetic--gpt-synthetic","stream":true,"max_tokens":1024,""" +
                        """"thinking":{"type":"adaptive","display":"summarized"},"output_config":{"effort":"max"},""" +
                        """"messages":[{"role":"user","content":"Send the synthetic effort probe."}]}""",
                )
            }.bodyAsText()
        } finally {
            client.close()
        }
    }
}
