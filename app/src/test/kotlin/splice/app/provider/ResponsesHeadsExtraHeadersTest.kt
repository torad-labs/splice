// Goal (fix 4 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): every Responses head sends
// the extra headers its config names, as the chat heads do (ChatArm's StaticChatHeaders): each header the provider's
// extra_headers names rides the head's requests, and the credential's own auth header wins over a configured one.
//
// These build each head from splice.toml the way the daemon builds it, pointed at a scripted backend, and send it a
// request the way Claude Code does: the hand-written API-key OpenAI Responses head, Grok's Responses head signed in
// with xAI's login and on an API key, and Muse.
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedProvider
import splice.head.scripted.Transport
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.topology.TopologyLoader
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val LANE_HEADER = "x-synthetic-lane"
private const val FILE_KEY = "synthetic-file-key"
private const val GROK_ACCESS = "synthetic-grok-access"

// The websocket transport, with no prefill round: the scripted backend answers every frame with an answer, and a
// prefill frame must be answered with none. A prefill frame rides the same handshake, so it changes nothing here.
private const val WEBSOCKET_NO_PREFILL = "websocket = true\nwebsocket_prefill = false"

// A sign-in that stays good for the whole test: 2100-01-01 in epoch milliseconds.
private const val FAR_EXPIRY_MS = 4_102_444_800_000L

/** What each head's provider table names: a lane header of its own, and an Authorization its credential must win
 *  over. */
private fun configured(lane: String) =
    """extra_headers = { "X-Synthetic-Lane" = "$lane", "Authorization" = "Bearer configured-not-the-credential" }"""

class ResponsesHeadsExtraHeadersTest {

    @Test
    fun `the API-key OpenAI Responses head sends its configured headers, over HTTP and on the websocket handshake`(
        @TempDir root: Path,
    ) = runBlocking {
        for (webSocket in listOf(false, true)) {
            val dir = root.resolve(if (webSocket) "ws" else "http")
            val rig = HeadRig.start(dir, key = "synthetic-openai") { backend ->
                val keyFile = Files.writeString(Files.createDirectories(dir).resolve("key"), FILE_KEY)
                """
                |[providers.synthetic-openai]
                |dialect = "openai-responses"
                |base_url = "${backend.baseUrl}/v1"
                |auth = { kind = "api-key", file = "${slashes(keyFile)}" }
                |${configured("openai-lane")}
                |${if (webSocket) "[providers.synthetic-openai.quirks]\n$WEBSOCKET_NO_PREFILL" else ""}
                |[[providers.synthetic-openai.models]]
                |id = "gpt-synthetic"
                |context_window = 200000
                |[heads.synthetic-openai]
                |provider = "synthetic-openai"
                |port = 31395
                |discovery_prefix = "claude-synthetic--"
                |pinned_model = "gpt-synthetic"
                """.trimMargin()
            }
            try {
                val request = rig.ask("claude-synthetic--gpt-synthetic")

                assertEquals(if (webSocket) Transport.WEBSOCKET else Transport.HTTP, request.transport)
                assertEquals("openai-lane", request.headers[LANE_HEADER], "the configured header rides (ws=$webSocket)")
                assertEquals("Bearer $FILE_KEY", request.headers["authorization"], "the key's own auth header wins")
            } finally {
                rig.stop()
            }
        }
    }

    @Test
    fun `Grok's Responses head signed in with xAI's login sends its configured headers`(@TempDir root: Path) =
        runBlocking {
            val rig = HeadRig.start(root, key = "synthetic-grok") { backend ->
                val authFile = Files.writeString(
                    root.resolve("grok-auth.json"),
                    """{"tokens":{"access_token":"$GROK_ACCESS","refresh_token":"synthetic-grok-refresh"},""" +
                        """"expires":$FAR_EXPIRY_MS}""",
                )
                """
                |[providers.synthetic-grok]
                |dialect = "openai-responses"
                |base_url = "${backend.baseUrl}/v1"
                |auth = { kind = "grok-oauth", file = "${slashes(authFile)}" }
                |${configured("grok-lane")}
                |[[providers.synthetic-grok.models]]
                |id = "grok-synthetic"
                |context_window = 256000
                |[heads.synthetic-grok]
                |provider = "synthetic-grok"
                |port = 31396
                |discovery_prefix = "claude-grok--"
                |pinned_model = "grok-synthetic"
                """.trimMargin()
            }
            try {
                val request = rig.ask("claude-grok--grok-synthetic")

                assertEquals("grok-lane", request.headers[LANE_HEADER], "the configured header rides")
                assertEquals("Bearer $GROK_ACCESS", request.headers["authorization"], "the sign-in's auth header wins")
            } finally {
                rig.stop()
            }
        }

    @Test
    fun `Grok's Responses head on an API key sends its configured headers`(@TempDir root: Path) = runBlocking {
        val rig = HeadRig.start(root, key = "synthetic-grok-key") { backend ->
            val keyFile = Files.writeString(root.resolve("key"), FILE_KEY)
            // A table not named xai that keys its cache by session is served by Grok's provider (ApiKeyResponsesArm).
            """
            |[providers.synthetic-grok-key]
            |dialect = "openai-responses"
            |base_url = "${backend.baseUrl}/v1"
            |auth = { kind = "api-key", file = "${slashes(keyFile)}" }
            |${configured("grok-key-lane")}
            |[providers.synthetic-grok-key.quirks]
            |cache_key = "session-id"
            |[[providers.synthetic-grok-key.models]]
            |id = "grok-synthetic"
            |context_window = 256000
            |[heads.synthetic-grok-key]
            |provider = "synthetic-grok-key"
            |port = 31397
            |discovery_prefix = "claude-grok--"
            |pinned_model = "grok-synthetic"
            """.trimMargin()
        }
        try {
            val request = rig.ask("claude-grok--grok-synthetic")

            assertEquals("grok-key-lane", request.headers[LANE_HEADER], "the configured header rides")
            assertEquals("Bearer $FILE_KEY", request.headers["authorization"], "the key's own auth header wins")
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `Muse sends the extra headers its config names`(@TempDir root: Path) = runBlocking {
        val rig = HeadRig.start(root, key = "claude-muse") { backend ->
            val authFile = Files.writeString(
                root.resolve("muse-auth.json"),
                """{"access_token":"synthetic-muse-account","api_key":"synthetic-muse-key"}""",
            )
            """
            |[providers.muse]
            |dialect = "openai-responses"
            |base_url = "${backend.baseUrl}/v1"
            |auth = { kind = "muse-oauth", file = "${slashes(authFile)}" }
            |extra_headers = { "X-Synthetic-Lane" = "muse-lane" }
            |[[providers.muse.models]]
            |id = "muse-synthetic"
            |context_window = 1000000
            |[heads.claude-muse]
            |provider = "muse"
            |port = 31398
            |discovery_prefix = "claude-muse--"
            |pinned_model = "muse-synthetic"
            """.trimMargin()
        }
        try {
            val request = rig.ask("claude-muse--muse-synthetic")

            assertEquals("muse-lane", request.headers[LANE_HEADER], "the configured header rides")
        } finally {
            rig.stop()
        }
    }

    private fun slashes(path: Path): String = path.toString().replace("\\", "/")
}

/** One head built from splice.toml as the daemon builds it, over a scripted backend. */
private class HeadRig(
    private val backend: ScriptedProvider,
    private val head: HeadServer,
    private val scope: CoroutineScope,
) {
    private val client = HttpClient(CIO)

    /** One request for [model], answered by the backend; returns what the backend received. */
    suspend fun ask(model: String): ProviderRequest {
        backend.script(reply { responsesAnswer("synthetic header answer") })
        val answer = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            bearerAuth("test-inference-token")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "synthetic-header-session")
            setBody(
                """{"model":"$model","stream":true,"max_tokens":1024,""" +
                    """"messages":[{"role":"user","content":"Send the synthetic header probe."}]}""",
            )
        }.bodyAsText()
        assertTrue(answer.contains("synthetic header answer"), "the head answered")
        return backend.requests.single()
    }

    suspend fun stop() {
        head.stop()
        client.close()
        backend.stop()
        scope.cancel()
    }

    companion object {
        /** The head [key] of the topology [toml] writes for a backend, built and listening. */
        suspend fun start(root: Path, key: String, toml: (ScriptedProvider) -> String): HeadRig {
            Files.createDirectories(root)
            val backend = ScriptedProvider(VirtualTime())
            backend.start()
            val topology = TopologyLoader.parse(toml(backend))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val paths = StatePaths(baseOverride = root.resolve("state"))
            val log = LogSink { }
            val noMint = MuseKeyMintCall { _, _ -> MuseMintAttempt.Denied("no mint in this test") }
            val headCfg = topology.heads.getValue(key)
            val providerCfg = topology.providers.getValue(headCfg.provider)
            val provider = ProviderAssembly(
                paths,
                scope,
                log = log,
                refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
                museArm = MuseResponsesArm(paths, log, scope, noMint),
            ).buildProvider(
                ProviderBuild(
                    key = key,
                    head = headCfg,
                    providerCfg = providerCfg,
                    catalog = providerCfg.catalogFor(headCfg),
                    faultPlan = UpstreamFaultPlan(
                        watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                        loginCommand = "$key login",
                    ),
                    cfg = ConfigService(paths, envReader = { null }).getConfig(key),
                ),
            ).provider
            val head = HeadServer(
                provider = provider,
                listenPort = 0,
                deps = headDeps(tmp = root, upstream = UpstreamClient(totalTimeoutMs = 30_000L))
                    .copy(policy = HeadDeps.HeadPolicy(stopDrainMs = 0)),
            )
            head.start()
            awaitListening(head.port)
            return HeadRig(backend, head, scope)
        }
    }
}
