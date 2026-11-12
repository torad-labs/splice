package splice.app.provider.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import splice.app.provider.codex.CodeModeMcpReplies
import splice.codemode.NativeCodeModeRuntime
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.mcp.McpToolResultStore
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Both boundaries are HTTP; the guest is the packaged native runtime, with no client or model inference. */
internal class CapturedMcpRig(tmp: Path, store: McpToolResultStore) {
    val upstream = CapturedMcpUpstream()
    private val logs = ConcurrentLinkedQueue<String>()
    private val runtime = NativeCodeModeRuntime()
    private val bridge = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            runtimes = { runtime },
            state = CodeModeStateLocation(
                tmp.resolve("records"),
                tmp.resolve("legacy.json"),
                mcpResults = CodeModeMcpReplies(store) { logs += it },
            ),
            log = { logs += it },
        ),
    )
    private val head = HeadServer(provider(), 0, headDeps(tmp, log = { logs += it }))
    private val client = HttpClient(CIO) { engine { requestTimeout = TimeUnit.SECONDS.toMillis(60) } }

    suspend fun start() = head.start()

    suspend fun send(session: String, messages: List<JsonObject>): List<JsonObject> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", session)
            contentType(ContentType.Application.Json)
            setBody(request(messages).toString())
        }
        val stream = response.bodyAsText()
        val diagnosis = "${stream.take(4_000)}; logs: ${logs.joinToString(" | ").takeLast(8_000)}"
        assertEquals(HttpStatusCode.OK, response.status, diagnosis)
        assertFalse("event: error" in stream, diagnosis)
        assertTrue("message_stop" in stream, diagnosis)
        val blocks = CapturedMcpBlocks()
        stream.lineSequence().filter { it.startsWith("data: ") }.forEach {
            blocks.take(Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject)
        }
        return blocks.values()
    }

    private fun request(messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", "claude-codex--gpt-5.6-sol")
        put("stream", true)
        put("max_tokens", 64)
        put("messages", JsonArray(messages))
        val tool = CapturedMcpPeer.tool()
        put(
            "tools",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("name", "mcp__capture__inspect")
                        put("description", tool.getValue("description"))
                        put("input_schema", tool.getValue("inputSchema"))
                    },
                ),
            ),
        )
    }

    private fun provider(): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            name = ProviderName("codex", "captured-MCP-test"),
            catalog = ModelCatalog(
                "claude-codex--",
                listOf(ModelEntry("gpt-5.6-sol", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            },
            locations = ProviderLocations(baseUrl = upstream.url),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
        codeMode = CodexCodeModeWiring(bridge, listOf("gpt-5.6-sol")),
        log = { logs += it },
    )

    suspend fun close() {
        try {
            head.stop()
        } finally {
            bridge.onProviderStop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }
}
