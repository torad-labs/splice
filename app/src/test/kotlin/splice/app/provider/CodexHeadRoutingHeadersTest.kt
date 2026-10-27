// Goal (fix 4 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): requests to the ChatGPT
// Codex backend carry the routing headers Codex sends, so the backend routes and caches a splice conversation the way
// it does a Codex one, and every Responses head sends the extra headers its config names.
//
// What Codex sends, from codex-rs (core/src/client.rs and codex-api/src/endpoint/responses.rs):
// - ChatGPT-Account-ID on every request of a ChatGPT sign-in. A config that turns it off keeps it off.
// - thread-id, and client_metadata.thread_id in the body, filled from one thread id.
// - x-client-request-id carrying that same thread id, on the HTTP POST (responses.rs:86-89) and on the websocket
//   handshake (client.rs:1519-1520).
// The prompt cache key stays as splice keys it today: the conversation's first message.
//
// These build a codex head from splice.toml the way the daemon builds it, from the provider and head tables setup
// writes for the codex profile (AddProfileCatalog's codex row: no quirks, no extra headers), pointed at a scripted
// backend, and send it requests the way Claude Code does.
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedProvider
import splice.head.scripted.Transport
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.topology.TopologyLoader
import splice.upstream.Provider
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val ACCOUNT_ID = "synthetic-chatgpt-account"
private const val FIRST_SESSION = "synthetic-routing-session-one"
private const val SECOND_SESSION = "synthetic-routing-session-two"
private const val OPENING = """[{"role":"user","content":"Open the synthetic routing probe."}]"""

// The websocket transport, with no prefill round: the scripted backend answers every frame with an answer, and a
// prefill frame must be answered with none. A prefill frame rides the same handshake, so it changes nothing here.
private const val WEBSOCKET = "websocket = true\nwebsocket_prefill = false"

class CodexHeadRoutingHeadersTest {

    @Test
    fun `a codex head as setup writes it sends the account id header, and one whose config turns it off does not`(
        @TempDir root: Path,
    ) = runBlocking {
        val setup = CodexRig.start(root.resolve("setup"))
        val off = CodexRig.start(root.resolve("off"), quirks = "account_id_header = false")
        try {
            setup.post(FIRST_SESSION, OPENING)
            off.post(FIRST_SESSION, OPENING)

            assertEquals(ACCOUNT_ID, setup.requests().single().headers["chatgpt-account-id"])
            assertNull(off.requests().single().headers["chatgpt-account-id"], "turned off in config, it stays off")
        } finally {
            setup.stop()
            off.stop()
        }
    }

    @Test
    fun `the thread-id header and the body's client_metadata thread id carry one value, and the cache key stays`(
        @TempDir root: Path,
    ) = runBlocking {
        val rig = CodexRig.start(root)
        try {
            rig.post(FIRST_SESSION, OPENING)
            rig.post(SECOND_SESSION, OPENING)

            val (first, second) = rig.requests()
            for (request in listOf(first, second)) {
                val thread = request.headers["thread-id"]
                assertNotNull(thread, "a session's request names its thread")
                assertEquals(thread, metadata(request)?.get("thread_id")?.jsonPrimitive?.content)
            }
            assertNotEquals(first.headers["thread-id"], second.headers["thread-id"], "two sessions, two threads")
            // Two sessions that open with the same words share the conversation's first-message key, as today.
            val keys = listOf(first, second).map { body(it)["prompt_cache_key"]?.jsonPrimitive?.content }
            assertNotNull(keys.first(), "the request carries a prompt cache key")
            assertEquals(keys.first(), keys.last(), "the prompt cache key stays the conversation's first-message key")
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `x-client-request-id goes out as the thread id on the HTTP post and on the websocket handshake`(
        @TempDir root: Path,
    ) = runBlocking {
        val http = CodexRig.start(root.resolve("http"))
        val socket = CodexRig.start(root.resolve("ws"), quirks = WEBSOCKET)
        try {
            http.post(FIRST_SESSION, OPENING)
            socket.post(FIRST_SESSION, OPENING)

            val post = http.requests().single()
            assertEquals(Transport.HTTP, post.transport)
            assertNotNull(post.headers["thread-id"])
            assertEquals(post.headers["thread-id"], post.headers["x-client-request-id"])
            val frame = socket.requests().single()
            assertEquals(Transport.WEBSOCKET, frame.transport)
            assertNotNull(frame.headers["thread-id"])
            assertEquals(frame.headers["thread-id"], frame.headers["x-client-request-id"])
        } finally {
            http.stop()
            socket.stop()
        }
    }

    @Test
    fun `a codex head sends the extra headers its config names, over HTTP and on the websocket handshake`(
        @TempDir root: Path,
    ) = runBlocking {
        val configured = """extra_headers = { "X-Synthetic-Lane" = "codex-lane" }"""
        val http = CodexRig.start(root.resolve("http"), extraHeaders = configured)
        val socket = CodexRig.start(root.resolve("ws"), quirks = WEBSOCKET, extraHeaders = configured)
        try {
            http.post(FIRST_SESSION, OPENING)
            socket.post(FIRST_SESSION, OPENING)

            val post = http.requests().single()
            assertEquals(Transport.HTTP, post.transport)
            assertEquals("codex-lane", post.headers["x-synthetic-lane"])
            val frame = socket.requests().single()
            assertEquals(Transport.WEBSOCKET, frame.transport)
            assertEquals("codex-lane", frame.headers["x-synthetic-lane"])
        } finally {
            http.stop()
            socket.stop()
        }
    }

    private fun body(request: ProviderRequest): JsonObject = Json.parseToJsonElement(request.body).jsonObject

    private fun metadata(request: ProviderRequest): JsonObject? = body(request)["client_metadata"] as? JsonObject
}

/** A codex head built from splice.toml as the daemon builds it, over a scripted backend. */
private class CodexRig(
    private val backend: ScriptedProvider,
    private val head: HeadServer,
    private val scope: CoroutineScope,
) {
    private val client = HttpClient(CIO)

    fun requests(): List<ProviderRequest> = backend.requests

    /** One request of [session] carrying [messages], answered by the backend; returns what the client read. */
    suspend fun post(session: String, messages: String): String {
        backend.script(reply { responsesAnswer("synthetic routing answer") })
        val answer = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            bearerAuth("test-inference-token")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", session)
            setBody("""{"model":"claude-codex--gpt-6-luna","stream":true,"max_tokens":1024,"messages":$messages}""")
        }.bodyAsText()
        assertTrue(answer.contains("synthetic routing answer"), "the head answered")
        return answer
    }

    suspend fun stop() {
        head.stop()
        client.close()
        backend.stop()
        scope.cancel()
    }

    companion object {
        /** A codex head over a scripted backend, built and listening, with the sign-in read from a file under
         *  [root], never the person's own. */
        suspend fun start(root: Path, quirks: String = "", extraHeaders: String = ""): CodexRig {
            Files.createDirectories(root)
            val backend = ScriptedProvider(VirtualTime())
            backend.start()
            val authFile = root.resolve("auth.json")
            Files.writeString(
                authFile,
                """{"tokens":{"access_token":"synthetic-access","account_id":"$ACCOUNT_ID"}}""",
            )
            val topology = TopologyLoader.parse(toml(backend, authFile, quirks, extraHeaders))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val head = HeadServer(
                provider = provider(root, topology, scope),
                listenPort = 0,
                deps = headDeps(tmp = root, upstream = UpstreamClient(totalTimeoutMs = 30_000L))
                    .copy(policy = HeadDeps.HeadPolicy(stopDrainMs = 0)),
            )
            head.start()
            awaitListening(head.port)
            return CodexRig(backend, head, scope)
        }

        /** The codex profile's tables as setup writes them ([quirks] and [extraHeaders] empty), with the base URL on
         *  the scripted backend. */
        private fun toml(backend: ScriptedProvider, authFile: Path, quirks: String, extraHeaders: String): String {
            val quirksTable = if (quirks.isEmpty()) "" else "[providers.codex.quirks]\n$quirks\n"
            return """
                |[providers.codex]
                |dialect = "openai-responses"
                |base_url = "${backend.baseUrl}"
                |auth = { kind = "chatgpt-oauth", file = "${authFile.toString().replace("\\", "/")}" }
                |$extraHeaders
                |$quirksTable
                |[[providers.codex.models]]
                |id = "gpt-6-sol"
                |label = "GPT-6 Sol"
                |context_window = 272000
                |[[providers.codex.models]]
                |id = "gpt-6-luna"
                |label = "GPT-6 Luna"
                |context_window = 272000
                |[heads.codex]
                |provider = "codex"
                |port = 31390
                |discovery_prefix = "claude-codex--"
                |pinned_model = "gpt-6-sol"
                |context_window = 272000
                |[heads.codex.claude]
                |command = "claudex"
            """.trimMargin()
        }

        /** The codex head's provider, built by the daemon's own assembly. */
        private fun provider(root: Path, topology: Topology, scope: CoroutineScope): Provider {
            val paths = StatePaths(baseOverride = root.resolve("state"))
            val headCfg = topology.heads.getValue("codex")
            val providerCfg = topology.providers.getValue("codex")
            return ProviderAssembly(
                paths,
                scope,
                log = {},
                refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
            ).buildProvider(
                ProviderBuild(
                    key = "codex",
                    head = headCfg,
                    providerCfg = providerCfg,
                    catalog = providerCfg.catalogFor(headCfg),
                    faultPlan = UpstreamFaultPlan(
                        watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                        loginCommand = "claudex login",
                    ),
                    cfg = ConfigService(paths, envReader = { null }).getConfig("codex"),
                ),
            ).provider
        }
    }
}
