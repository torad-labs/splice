// NEW: V4-457 — exact posted bytes and allocation through the real head with synthetic loopback transport.
package splice.head

import com.sun.management.ThreadMXBean
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.JsonWire
import splice.dialect.responses.ReasoningSettings
import splice.upstream.BuiltTurn
import splice.upstream.FoldPolicy
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RoundInterceptor
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HeadSerializationTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the real head counts substituted post UTF8 bytes on both collect and stream paths`() = runBlocking {
        val upstream = MockChatGptUpstream()
        HttpClient(CIO).use { client ->
            try {
                for (folding in listOf(false, true)) {
                    verifyPostBytes(client, upstream, folding)
                }
            } finally {
                upstream.stop()
            }
        }
    }

    private suspend fun verifyPostBytes(client: HttpClient, upstream: MockChatGptUpstream, folding: Boolean) {
        val deps = headDeps(tmp).copy(stores = headStores(tmp, suffix = "-fold-$folding"))
        val provider = SubstitutingSerializationProvider(serializationProvider(upstream.baseUrl), folding)
        val head = HeadServer(provider, 0, deps)
        head.start()
        try {
            for ((index, stream) in listOf(false, true).withIndex()) {
                val request = buildJsonObject {
                    put("model", "synthetic-synthetic")
                    put("stream", stream)
                    put("max_tokens", 64)
                    put(
                        "messages",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", "hello")
                                },
                            ),
                        ),
                    )
                }
                val reply = sendSerialization(client, head.port, request)
                assertTrue(if (stream) reply.contains("message_stop") else reply.contains("\"content\""), reply)
                assertEquals(200, head.providerAnswer()?.status, "the real HTTP path publishes its provider answer")
                assertTrue((head.providerAnswer()?.observedAtEpochMs ?: 0L) > 0L)
                // Terminal delivery precedes perf publication; a drain cannot settle a not-yet-submitted row.
                withTimeout(5.seconds) {
                    while (deps.stores.perfStats.tailNumeric(10).size <= index) delay(10.milliseconds)
                }
                val wire = upstream.upstreamBodies.last().second
                assertTrue(wire.contains("café 🧪"), "the interceptor must replace the posted body")
                val bytes = wire.toByteArray(Charsets.UTF_8).size.toLong()
                assertTrue(bytes > wire.length, "the fixture must distinguish UTF8 bytes from UTF16 length")
                assertEquals(bytes, deps.stores.perfStats.tailNumeric(1).single()[PerfKeys.UPSTREAM_REQ_BYTES])
            }
        } finally {
            head.stop()
            assertTrue(AsyncFileIo.drain())
        }
    }

    @Test
    fun `one real head request keeps large prompt allocation bounded across its worker threads`(
        reporter: TestReporter,
    ) = runBlocking {
        val upstream = MockChatGptUpstream()
        val head = HeadServer(serializationProvider(upstream.baseUrl), 0, headDeps(tmp))
        val request = serializationRequest()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported && bean.isThreadCpuTimeSupported)
        bean.isThreadAllocatedMemoryEnabled = true
        bean.isThreadCpuTimeEnabled = true
        HttpClient(CIO).use { client ->
            head.start()
            try {
                repeat(5) { assertTrue(sendSerialization(client, head.port, request).contains("\"content\"")) }
                assertTrue(AsyncFileIo.drain())
                val upstreamBytes = upstream.upstreamBodies.last().second.toByteArray(Charsets.UTF_8).size
                assertTrue(upstreamBytes in 1_400_000..1_500_000, "the upstream must receive the whole large body")
                reporter.publishEntry("real_head_upstream_body_bytes", upstreamBytes.toString())
                val before = threadCosts(bean)
                val allocatedBefore = bean.totalThreadAllocatedBytes
                assertTrue(allocatedBefore >= 0, "the JVM must expose its total allocation counter")
                val reply = sendSerialization(client, head.port, request)
                assertTrue(AsyncFileIo.drain())
                val after = threadCosts(bean)
                val allocated = bean.totalThreadAllocatedBytes - allocatedBefore
                val cpuNs = after.entries.sumOf { (thread, cost) -> cost - (before[thread] ?: 0) }
                reporter.publishEntry("real_head_request_allocated_bytes", allocated.toString())
                reporter.publishEntry("real_head_request_cpu_ns", cpuNs.toString())
                assertTrue(reply.contains("\"content\""), reply)
                // Includes client transport, parsing, preparation, upstream POST, response and settled file-lane work.
                assertTrue(
                    allocated < 32 * 1024 * 1024,
                    "real_head_request_allocated_bytes=$allocated; budget=33554432",
                )
            } finally {
                head.stop()
                upstream.stop()
                assertTrue(AsyncFileIo.drain())
            }
        }
    }

    @Test
    fun `both admission routes refuse deeply nested JSON without calling upstream`() = runBlocking {
        val upstream = MockChatGptUpstream()
        val head = HeadServer(serializationProvider(upstream.baseUrl), 0, headDeps(tmp))
        HttpClient(CIO).use { client ->
            head.start()
            try {
                val body = nestedRequest(1_000_000)
                for (route in listOf("messages", "messages/count_tokens")) {
                    val response = client.post("http://127.0.0.1:${head.port}/v1/$route") {
                        header("Authorization", "Bearer test-inference-token")
                        header("Content-Type", "application/json")
                        setBody(body)
                    }
                    assertEquals(400, response.status.value, response.bodyAsText().take(200))
                    assertTrue(response.bodyAsText().contains("invalid request body"))
                }
                assertTrue(upstream.upstreamBodies.isEmpty(), "rejected bodies must never reach upstream")
            } finally {
                head.stop()
                upstream.stop()
            }
        }
    }

    @Test
    fun `a request just below the nesting bound reaches upstream byte identical`() = runBlocking {
        val upstream = MockChatGptUpstream()
        val base = serializationProvider(upstream.baseUrl)
        val provider = object : Provider by base {
            override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
                base.buildTurn(body, compact, sessionId).copy(requestBody = body.raw, routingFields = emptySet())
        }
        val head = HeadServer(provider, 0, headDeps(tmp))
        HttpClient(CIO).use { client ->
            head.start()
            try {
                val body = nestedRequest(JsonWire.MAX_REQUEST_DEPTH - 1)
                val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    header("Authorization", "Bearer test-inference-token")
                    header("Content-Type", "application/json")
                    setBody(body)
                }
                assertEquals(200, response.status.value, response.bodyAsText())
                assertEquals(body, upstream.upstreamBodies.single().second)
            } finally {
                head.stop()
                upstream.stop()
            }
        }
    }

    private fun threadCosts(bean: ThreadMXBean): Map<Long, Long> =
        bean.allThreadIds.associateWith { thread -> bean.getThreadCpuTime(thread).coerceAtLeast(0) }
}

private fun serializationProvider(baseUrl: String): Provider = TestResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "synthetic", label = "synthetic"),
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic-",
            models = listOf(ModelEntry("synthetic", contextWindow = 2_000_000)),
            defaultContextWindow = 2_000_000,
        ),
        pinnedModel = "synthetic",
        auth = ClientAuthProvider("synthetic"),
        locations = ProviderLocations(baseUrl = baseUrl),
        watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.OFF, false, null, null),
)

private class SubstitutingSerializationProvider(
    private val base: Provider,
    private val folding: Boolean,
) : Provider by base {
    override fun foldPolicy(meta: TurnMeta): FoldPolicy? = if (folding) FoldPolicy { null } else null

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
        base.buildTurn(body, compact, sessionId).let { built ->
            built.copy(
                roundInterceptor = RoundInterceptor { wire, _, post ->
                    val actual = Json.parseToJsonElement(wire).jsonObject + ("instructions" to JsonPrimitive("café 🧪"))
                    post(JsonWire.string(JsonObject(actual)))
                },
            )
        }
}

private fun nestedRequest(depth: Int): String =
    """{"model":"synthetic-synthetic","stream":false,"max_tokens":64,""" +
        """"messages":[{"role":"user","content":"hello"}],"extra":""" +
        "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1) + "}"

private fun serializationRequest(): JsonObject = buildJsonObject {
    put("model", "synthetic-synthetic")
    put("stream", false)
    put("max_tokens", 64)
    val text = "x".repeat(22_000)
    put(
        "messages",
        JsonArray(
            List(64) {
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            ),
                        ),
                    )
                }
            },
        ),
    )
}

private suspend fun sendSerialization(client: HttpClient, port: Int, body: JsonObject): String =
    client.post("http://127.0.0.1:$port/v1/messages") {
        header("Authorization", "Bearer test-inference-token")
        header("Content-Type", "application/json")
        header("x-claude-code-session-id", "synthetic-serialization")
        setBody(JsonWire.string(body))
    }.bodyAsText()
