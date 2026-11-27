package splice.head.observe

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.turn.ObservedRequest
import splice.core.turn.ObservedResponse
import splice.core.turn.ObservedStreamFrame
import splice.core.turn.ObservedToolCall
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.ObservedUsage
import splice.core.turn.TurnObservation
import splice.core.turn.TurnObserverOffer
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.GatewayTurn
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.OtherAnswer
import splice.head.OtherHeads
import splice.head.ProviderRuntime
import splice.head.awaitListening
import splice.head.headDeps
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MODEL = "native-model"
private const val SESSION = "session-observed"
private const val OWNER = "ea0cef22-6e37-4092-8b79-f32e58304181"
private const val TOOL = "mcp__fixture__lookup"
private const val ID = "toolu_observed"

class TurnObserversTest(@param:TempDir private val root: Path) {

    @Test
    fun `an observed native stream reports structural metadata without changing the wire`() = runTest {
        val observer = Recorder()
        val posted = CopyOnWriteArrayList<String>()
        withUpstream(posted = posted) { upstream ->
            val plain = turn(upstream, "native", NoTurnObservers)
            val watched = turn(upstream, "native", observer)
            assertEquals(200, watched.first)
            assertEquals(plain.first, watched.first)
            // Only the proxy's fresh message id should be different.
            assertEquals(normalized(plain.second), normalized(watched.second))

            val events = observer.finished.await()
            val start = events.first() as ObservedTurnFacts
            assertEquals(SESSION, start.sessionId)
            assertEquals(OWNER, start.launchOwner)
            assertEquals("native", start.head)
            assertEquals(MODEL, start.model)
            assertEquals("agent-observed", start.agent)
            assertEquals(false, start.compact)
            val request = events[1] as ObservedRequest
            assertEquals(1, request.round)
            assertEquals(MODEL, request.model)
            assertEquals(posted.first(), posted.last(), "observing does not alter the upstream request")
            val response = events[2] as ObservedResponse
            assertEquals(200, response.status)
            assertEquals(1L, response.attempt)
            val frames = events.filterIsInstance<ObservedStreamFrame>()
            val delivered = watched.second.split("\n\n").filter(String::isNotEmpty)
            assertEquals(delivered.map { it.substringBefore('\n').removePrefix("event: ") }, frames.map { it.type })
            assertEquals(delivered.map { (it + "\n\n").encodeToByteArray().size.toLong() }, frames.map { it.bytes })
            assertEquals((1L..frames.size.toLong()).toList(), frames.map { it.seq })
            val tool = events.filterIsInstance<ObservedToolCall>().single()
            assertEquals(ID, tool.id)
            assertEquals(TOOL, tool.name)
            assertTrue(events.indexOf(tool) in 3 until events.lastIndex)
            val end = events.last() as ObservedTurnEnd
            assertEquals(OutcomeTag.OK.wire, end.outcome)
            assertEquals(ObservedUsage(7 + 2 + 1L, 3, 2, 1), end.usage)
            assertTrue(end.durationMs >= 0)
        }
    }

    @Test
    fun `a retried round reports both provider statuses under one logical send`() = runTest {
        val observer = Recorder()
        withUpstream(refusals = listOf(503)) { upstream ->
            assertEquals(200, turn(upstream, "native", observer, TurnRequest(stream = false)).first)
            val events = observer.finished.await()
            assertEquals(listOf(1), events.filterIsInstance<ObservedRequest>().map { it.round })
            assertEquals(
                listOf(503 to 1L, 200 to 2L),
                events.filterIsInstance<ObservedResponse>().map { it.status to it.attempt },
            )
        }
    }

    @Test
    fun `a collected native tool is observed without SSE frames`() = runTest {
        val observer = Recorder()
        withUpstream { upstream ->
            val (status, body) = turn(upstream, "native", observer, TurnRequest(stream = false))
            assertEquals(200, status)
            assertTrue(body.contains(ID))
            val events = observer.finished.await()
            assertEquals(
                listOf(
                    ObservedTurnFacts::class,
                    ObservedRequest::class,
                    ObservedResponse::class,
                    ObservedToolCall::class,
                    ObservedTurnEnd::class,
                ),
                events.map { it::class },
            )
            assertEquals(TOOL, (events[3] as ObservedToolCall).name)
        }
    }

    @Test
    fun `a local refusal with a perf row gives only start and complete`() = runTest {
        val observer = Recorder()
        withUpstream { upstream ->
            val (status, _) = turn(upstream, "native", observer, TurnRequest(malformed = true))
            assertEquals(400, status)
            val events = observer.finished.await()
            assertEquals(listOf(ObservedTurnFacts::class, ObservedTurnEnd::class), events.map { it::class })
            assertEquals(SESSION, (events.first() as ObservedTurnFacts).sessionId)
        }
    }

    @Test
    fun `only the answering head observes a relayed turn`() = runTest {
        val incoming = Recorder()
        val answering = Recorder()
        withUpstream { upstream ->
            val runtime = ProviderRuntime(provider(upstream, "answering"), deps(answering))
            runtime.start()
            try {
                val other = OtherHeads { ask ->
                    OtherAnswer { call ->
                        val request = GatewayTurn(body(false).encodeToByteArray(), "claude", null, asked = ask.model)
                        runtime.serve(call, request)
                    }
                }
                val request = TurnRequest(stream = false, others = other)
                assertEquals(200, turn(upstream, "incoming", incoming, request).first)
                assertEquals("answering", (answering.finished.await().first() as ObservedTurnFacts).head)
                assertEquals(emptyList<Any>(), incoming.events)
            } finally {
                runtime.stop()
            }
        }
    }

    @Test
    fun `observer errors and cancellation cannot interrupt the client or later events`() = runTest {
        val failures = CopyOnWriteArrayList<String>()
        val audit = TurnObserverOffer(LogSink { failures.add(it) })
        val observer = Recorder(failOnEdges = true)
        val brokenStart = object : TurnObservers {
            override val offerAudit = audit
            override fun begin(facts: ObservedTurnFacts): TurnObservation = throw AssertionError("observer failed")
        }
        val brokenEdges = object : TurnObservers by observer {
            override val offerAudit = audit
        }
        withUpstream { upstream ->
            val plain = turn(upstream, "native", NoTurnObservers)
            val broken = turn(upstream, "native", brokenStart)
            val throwing = turn(upstream, "native", brokenEdges)
            assertEquals(200, plain.first)
            for (watched in listOf(broken, throwing)) {
                assertEquals(plain.first, watched.first)
                assertEquals(normalized(plain.second), normalized(watched.second))
            }
            assertEquals(OutcomeTag.OK.wire, (observer.finished.await().last() as ObservedTurnEnd).outcome)
            assertEquals(listOf("[mods] turn observation failed; further failures omitted\n"), failures)
        }
    }

    private fun deps(observer: TurnObservers): HeadDeps = headDeps(
        tmp = Files.createDirectory(root.resolve(UUID.randomUUID().toString())),
        upstream = UpstreamClient(totalTimeoutMs = 30_000L, pacing = RetryPacing(waiter = Waiter { })),
        seams = HeadDeps.HeadSeams(reports = HeadDeps.HeadReports(observers = observer)),
    )

    private suspend fun turn(
        upstream: HttpServer,
        head: String,
        observer: TurnObservers,
        request: TurnRequest = TurnRequest(),
    ): Pair<Int, String> {
        val deps = deps(observer)
        val server = HeadServer(
            provider(upstream, head),
            0,
            deps.copy(traffic = deps.traffic.copy(others = request.others)),
        )
        server.start()
        try {
            awaitListening(server.port)
            return HttpClient(CIO).use { client ->
                val reply = client.post("http://127.0.0.1:${server.port}/v1/messages") {
                    bearerAuth("test-inference-token")
                    header("Content-Type", "application/json")
                    header("x-claude-code-session-id", SESSION)
                    header("x-claude-code-agent-id", "agent-observed")
                    header("x-splice-launch-owner", OWNER)
                    header("Cookie", "private-cookie=not-an-event")
                    setBody(if (request.malformed) "{" else body(request.stream))
                }
                reply.status.value to reply.bodyAsText()
            }
        } finally {
            server.stop()
        }
    }

    private fun normalized(sse: String): String =
        sse.replace(Regex("""("id":")msg_[^"]+"""), "$1<message-id>")

    private fun body(stream: Boolean): String =
        """{"model":"$MODEL","stream":$stream,"max_tokens":64,"messages":[{"role":"user","content":"héllo"}],""" +
            """"tools":[{"name":"$TOOL","input_schema":{"type":"object"}}]}"""

    private fun provider(upstream: HttpServer, head: String): PassthroughProvider = PassthroughProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = head, label = head),
            catalog = ModelCatalog("fixture--", listOf(ModelEntry(MODEL, "Native", contextWindow = 200_000)), 200_000),
            pinnedModel = MODEL,
            auth = ObserverTestAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        quirks = PassthroughQuirks(providerTag = head),
    )

    private suspend fun withUpstream(
        posted: MutableList<String>? = null,
        refusals: List<Int> = emptyList(),
        block: suspend (HttpServer) -> Unit,
    ) {
        val status = ArrayDeque(refusals)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            val request = exchange.requestBody.use { it.readAllBytes() }
            posted?.add(request.decodeToString())
            val refusal = status.removeFirstOrNull()
            if (refusal != null) {
                val error = """{"type":"error","error":{"type":"api_error","message":"synthetic outage"}}"""
                val bytes = error.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(refusal, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            val bytes = nativeToolResponse().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Set-Cookie", "provider-cookie=not-an-event")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun nativeToolResponse(): String = listOf(
        "message_start" to """{"message":{"usage":{"input_tokens":7,""" +
            """"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}}""",
        "content_block_start" to """{"index":0,"content_block":""" +
            """{"type":"tool_use","id":"$ID","name":"$TOOL","input":{}}}""",
        "content_block_delta" to """{"index":0,"delta":""" +
            """{"type":"input_json_delta","partial_json":"{\"q\":\"privé\"}"}}""",
        "content_block_stop" to """{"index":0}""",
        "message_delta" to """{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":3}}""",
        "message_stop" to "{}",
    ).joinToString("") { (type, fields) ->
        val tail = if (fields == "{}") "}" else ",${fields.drop(1)}"
        "event: $type\ndata: {\"type\":\"$type\"$tail\n\n"
    }
}

private data class TurnRequest(
    val stream: Boolean = true,
    val malformed: Boolean = false,
    val others: OtherHeads = OtherHeads { null },
)

private class ObserverTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("fixture-provider-key", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixture")
}

private class Recorder(private val failOnEdges: Boolean = false) : TurnObservers, TurnObservation {
    val events = CopyOnWriteArrayList<Any>()
    val finished = CompletableDeferred<List<Any>>()

    override fun begin(facts: ObservedTurnFacts): TurnObservation = apply { events.add(facts) }

    override fun requestSent(facts: ObservedRequest) {
        events.add(facts)
        if (failOnEdges) throw CancellationException("not a cancellation of the turn")
    }

    override fun responseReceived(facts: ObservedResponse) {
        events.add(facts)
        if (failOnEdges) throw AssertionError("observer failed")
    }

    override fun frame(facts: ObservedStreamFrame) {
        events.add(facts)
        if (failOnEdges) throw AssertionError("observer failed")
    }

    override fun toolCall(facts: ObservedToolCall) {
        events.add(facts)
        if (failOnEdges) throw AssertionError("observer failed")
    }

    override fun completed(facts: ObservedTurnEnd) {
        events.add(facts)
        finished.complete(events.toList())
        if (failOnEdges) throw AssertionError("observer failed")
    }
}
