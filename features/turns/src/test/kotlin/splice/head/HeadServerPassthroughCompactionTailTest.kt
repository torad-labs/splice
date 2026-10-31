// v0.4.0 FEATURES.md §7 on the anthropic passthrough wire, through the REAL HeadServer and a real HTTP upstream: a
// compaction carries the operator's text as one block after the client's own last user message, with everything the
// client sent before it untouched, and an ordinary turn goes upstream as the client sent it.
package splice.head

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.compaction.CompactionTail
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val OPERATOR_TEXT = "KEEP EVERY OPEN CONSTRAINT VERBATIM"
private const val SUMMARIZER = "You are tasked with summarizing conversations for another agent."

private const val STREAM =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n" +
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
        "\"content_block\":{\"type\":\"text\"}}\n\n" +
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
        "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
        "event: message_delta\ndata: {\"type\":\"message_delta\"," +
        "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

private class PassthroughKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("held-secret", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerPassthroughCompactionTailTest {

    private val received = CopyOnWriteArrayList<String>()
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var head: HeadServer

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        upstream.createContext("/v1/messages") { ex ->
            received += ex.requestBody.readAllBytes().decodeToString()
            val body = STREAM.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstream.start()
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "anthropic", label = "claude-splice"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-splice--",
                    models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "claude-fable-5",
                auth = PassthroughKeyAuth(),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
        )
        val tail = CompactionTail(CompactionInstructions(CompactionConfig(instructions = OPERATOR_TEXT), tmp))
        head = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000),
                seams = HeadDeps.HeadSeams(session = HeadDeps.SessionSeams(compactionTail = tail)),
            ),
        )
        runBlocking { head.start() }
    }

    @AfterAll
    fun tearDown() {
        runBlocking { head.stop() }
        upstream.stop(0)
        client.close()
    }

    private suspend fun send(system: String): String {
        val before = received.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-passthrough")
            setBody(
                """{"model":"claude-splice--claude-fable-5","stream":true,"max_tokens":64,"system":"$system",""" +
                    """"messages":[{"role":"user","content":[{"type":"text","text":"first"}]},""" +
                    """{"role":"assistant","content":[{"type":"text","text":"reply"}]},""" +
                    """{"role":"user","content":[{"type":"text","text":"carry on"}]}]}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        return received.drop(before).single()
    }

    @Test
    fun `a compaction carries the text after the last user message, an ordinary turn goes untouched`() = runBlocking {
        val compaction = Json.parseToJsonElement(send(SUMMARIZER)).jsonObject
        val ordinary = send("You are a test.")

        val last = compaction.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
        val texts = last.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(listOf("carry on", OPERATOR_TEXT), texts)
        assertEquals(3, compaction.getValue("messages").jsonArray.size, "no message was added or dropped")
        assertFalse(ordinary.contains(OPERATOR_TEXT), "an ordinary turn never carries it")
    }
}
