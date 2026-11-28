package splice.head.turn

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.wire.HttpStatus
import splice.core.wire.SHOULD_RETRY_HEADER
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.awaitListening
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val HTTP_REFUSAL_WORDS = "Content blocked by provider policy."
private const val HTTP_MODEL = "fixture-model"
private const val HTTP_CONTEXT_WINDOW = 272_000L
private const val HTTP_EXEC_TOOL = "exec"
private const val HTTP_UPSTREAM_TIMEOUT_MS = 30_000L
private const val HTTP_OK = 200
private const val HTTP_WATCHDOG_SECONDS = 10
private const val HTTP_WAIT_SECONDS = 15L

class CodeModeSourceRefusalHttpTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `content-filtered native code-mode exec source returns 400 with x-should-retry false over HTTP`(
        stream: Boolean,
        @TempDir tempDir: Path,
    ) = runBlocking {
        val upstream = sseUpstream()
        val bridge = bridge(tempDir)
        val head = headServer(tempDir, "http://127.0.0.1:${upstream.address.port}", bridge)
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        try {
            head.start()
            awaitListening(head.port)
            val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                header("Content-Type", "application/json")
                setBody(httpBody(stream))
            }
            assertRefusalHttp(response.status.value, response.headers[SHOULD_RETRY_HEADER], response.bodyAsText())
        } finally {
            client.close()
            head.stop()
            bridge.onHeadStop()
            upstream.stop(0)
        }
    }

    @Test
    fun `replayed held non-stream content refusal keeps x-should-retry false over HTTP`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val detached = CompletableDeferred<Unit>()
        val matched = CompletableDeferred<Unit>()
        val replayed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val upstream = sseUpstream {
            entered.complete(Unit)
            release.await(HTTP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        val bridge = bridge(tempDir)
        val head = headServer(tempDir, "http://127.0.0.1:${upstream.address.port}", bridge) { line ->
            if ("client left mid-turn" in line) detached.complete(Unit)
            if ("collect non-stream retry matches a detached one" in line) matched.complete(Unit)
            if ("served from its hold" in line) replayed.complete(Unit)
        }
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val payload = httpBody(stream = false)
        try {
            head.start()
            awaitListening(head.port)
            val first = rawPost(head.port, payload)
            withTimeout(HTTP_WAIT_SECONDS.seconds) { entered.await() }
            first.close()
            withTimeout(HTTP_WAIT_SECONDS.seconds) { detached.await() }
            val follower = async {
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    header("Content-Type", "application/json")
                    header("x-claude-code-session-id", "fixture-replay-session")
                    setBody(payload)
                }
            }
            withTimeout(HTTP_WAIT_SECONDS.seconds) { matched.await() }
            release.countDown()
            val response = withTimeout(HTTP_WAIT_SECONDS.seconds) { follower.await() }
            assertRefusalHttp(response.status.value, response.headers[SHOULD_RETRY_HEADER], response.bodyAsText())
            withTimeout(HTTP_WAIT_SECONDS.seconds) { replayed.await() }
        } finally {
            release.countDown()
            client.close()
            head.stop()
            bridge.onHeadStop()
            upstream.stop(0)
        }
    }

    private fun assertRefusalHttp(status: Int, shouldRetry: String?, body: String) {
        assertEquals(HttpStatus.BAD_REQUEST, status, body)
        assertEquals("false", shouldRetry, body)
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        assertEquals("invalid_request_error", error.getValue("type").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue(message.contains(HTTP_REFUSAL_WORDS), message)
        assertFalse(message.contains("runtime start was interrupted"), message)
    }

    private fun headServer(
        tempDir: Path,
        baseUrl: String,
        bridge: CodexCodeModeBridge,
        log: (String) -> Unit = {},
    ): HeadServer {
        val base = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "fixture-head", label = "fixture-head"),
                catalog = ModelCatalog(
                    discoveryPrefix = "fixture-prefix--",
                    models = listOf(ModelEntry(HTTP_MODEL, contextWindow = HTTP_CONTEXT_WINDOW)),
                    defaultContextWindow = HTTP_CONTEXT_WINDOW,
                ),
                pinnedModel = HTTP_MODEL,
                auth = FixedAuth,
                locations = ProviderLocations(baseUrl = baseUrl),
                watchdog = WatchdogBudget(
                    HTTP_WATCHDOG_SECONDS.seconds,
                    HTTP_WATCHDOG_SECONDS.seconds,
                    HTTP_WATCHDOG_SECONDS.seconds,
                ),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.OFF, false, "high", null),
        )
        val provider = object : Provider by base {
            override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
                val built = base.buildTurn(body, compact, sessionId)
                val turn = CodexCodeModeBridge.Turn(
                    sessionId = sessionId ?: "fixture-session",
                    conversationKey = "fixture-conversation",
                    model = HTTP_MODEL,
                    tools = setOf("Read"),
                )
                return built.copy(roundInterceptor = bridge.interceptor(turn, disableParallel = false))
            }
        }
        return HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(
                tmp = tempDir,
                upstream = UpstreamClient(totalTimeoutMs = HTTP_UPSTREAM_TIMEOUT_MS),
                log = log,
            ),
        )
    }

    private fun bridge(tempDir: Path): CodexCodeModeBridge {
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        return CodexCodeModeBridge(CodeModeBridgeConfig({ UnusedNativeRuntime() }, state))
    }

    private fun sseUpstream(beforeReply: () -> Unit = {}): HttpServer {
        val sse = refusalSse()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                beforeReply()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(HTTP_OK, sse.size.toLong())
                exchange.responseBody.use { it.write(sse) }
            }
            start()
        }
    }

    private fun refusalSse(): ByteArray = buildString {
        append("event: response.output_item.added\n")
        append(
            """data: {"type":"response.output_item.added","output_index":0,"item":{""" +
                """"type":"custom_tool_call","id":"item-1","call_id":"call-1","name":"$HTTP_EXEC_TOOL","input":""}}""",
        )
        append("\n\n")
        append("event: response.custom_tool_call_input.delta\n")
        append(
            """data: {"type":"response.custom_tool_call_input.delta","output_index":0,""" +
                """"delta":"await tools.Read({});"}""",
        )
        append("\n\n")
        append("event: response.failed\n")
        append(
            """data: {"type":"response.failed","response":{"status":"failed","error":{""" +
                """"code":"content_policy_violation","message":"$HTTP_REFUSAL_WORDS"}}}""",
        )
        append("\n\n")
    }.toByteArray()

    private fun httpBody(stream: Boolean): String =
        """{"model":"fixture-prefix--$HTTP_MODEL","stream":$stream,"max_tokens":256,""" +
            """"messages":[{"role":"user","content":"go"}]}"""

    private fun rawPost(port: Int, json: String): Socket = Socket("127.0.0.1", port).apply {
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "x-claude-code-session-id: fixture-replay-session\r\n" +
            "Content-Length: ${json.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + json
        getOutputStream().write(request.toByteArray())
        getOutputStream().flush()
    }

    private object FixedAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("tok-fixture", "acct-fixture")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
    }

    private class UnusedNativeRuntime : NativeCodeModeLifecycle {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("unused")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = error("unfinished source must not start a native cell")

        override fun close() = Unit
    }
}
