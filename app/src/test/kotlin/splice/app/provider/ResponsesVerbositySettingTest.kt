// Fix 5 (Oct 27, artifacts/responses-gaps-2026-10-27/goals.md): a person sets the verbosity of any Responses head in
// splice.toml, as Codex's model_verbosity, and a head with no setting sends what it sends today. Codex sends the
// setting as the request's text.verbosity (codex-rs core/src/client.rs, model_verbosity before the model's default).
//
// These build the hand-written API-key OpenAI Responses head from splice.toml the way the daemon builds it, its knobs
// read through the same layers (TopologyKnobLayer for [defaults], [heads.<key>.overrides] per head), point it at a
// scripted backend, and send it a request the way Claude Code does.
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
import org.junit.jupiter.api.Assertions.assertNull
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

class ResponsesVerbositySettingTest {

    @Test
    fun `the API-key OpenAI Responses head sends the verbosity its own overrides set`(@TempDir root: Path) =
        runBlocking {
            val sent = VerbosityRig.verbositySent(root, "[heads.$HEAD.overrides]\nverbosity = \"high\"")
            assertEquals("high", sent, "the head's [heads.$HEAD.overrides] verbosity rides its request")
        }

    @Test
    fun `the API-key OpenAI Responses head sends the verbosity splice toml's defaults set`(@TempDir root: Path) =
        runBlocking {
            val sent = VerbosityRig.verbositySent(root, "[defaults]\nverbosity = \"medium\"")
            assertEquals("medium", sent, "[defaults] verbosity reaches every head that has none of its own")
        }

    @Test
    fun `an API-key OpenAI Responses head with no setting sends no verbosity, as it does today`(@TempDir root: Path) =
        runBlocking {
            assertNull(VerbosityRig.verbositySent(root, ""), "no setting, no text.verbosity")
        }
}

/** One API-key OpenAI Responses head built from splice.toml as the daemon builds it, over a scripted backend. */
private object VerbosityRig {
    /** The text.verbosity the head's one request carried, with [settings] written into its splice.toml. */
    suspend fun verbositySent(root: Path, settings: String): String? {
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
            backend.script(reply { responsesAnswer("synthetic verbosity answer") })
            assertTrue(ask(head.port).contains("synthetic verbosity answer"), "the head answered")
            val body = Json.parseToJsonElement(backend.requests.single().body).jsonObject
            return ((body["text"] as? JsonObject)?.get("verbosity") as? JsonPrimitive)?.content
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

    /** One request to the head on [port], as Claude Code sends it, and the head's answer. */
    private suspend fun ask(port: Int): String {
        val client = HttpClient(CIO)
        try {
            return client.post("http://127.0.0.1:$port/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", "synthetic-verbosity-session")
                setBody(
                    """{"model":"claude-synthetic--gpt-synthetic","stream":true,"max_tokens":1024,""" +
                        """"messages":[{"role":"user","content":"Send the synthetic verbosity probe."}]}""",
                )
            }.bodyAsText()
        } finally {
            client.close()
        }
    }
}
