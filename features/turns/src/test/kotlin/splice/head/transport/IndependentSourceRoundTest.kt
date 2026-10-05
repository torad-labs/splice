package splice.head.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.head.HeadServer
import splice.head.RecordingSink2
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.upstream.BuiltTurn
import splice.upstream.InterceptedRoundPost
import splice.upstream.LifecycleScope
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundInterceptor
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.retry.InflightGate
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WireSink
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class IndependentSourceRoundTest {
    @Test
    @Timeout(20)
    fun `a completed client step retains raw admission and reads the upstream terminal after client close`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = GatedSourceServer()
        val interceptor = SourceInterceptor()
        val provider = SourceProvider(base(upstream.url), interceptor)
        val gate = InflightGate({ 2 })
        val deps = headDeps(tmp, gate = gate)
        val head = HeadServer(provider, 0, deps)
        val client = HttpClient(CIO)
        head.start()
        try {
            val request = async {
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    bearerAuth("test-inference-token")
                    setBody(
                        """{"model":"claude-codex--stream-test","stream":true,"max_tokens":64,
                            "messages":[{"role":"user","content":"go"}]}""",
                    )
                }.bodyAsText()
            }
            withTimeout(5_000) { interceptor.observed.await() }
            val body = withTimeout(5_000) { request.await() }
            assertTrue(body.contains("tool_use"))
            assertTrue(body.contains("message_stop"))
            assertFalse(body.contains("await tools.Read"), "exec source is not client text")
            client.close()
            assertEquals(1, gate.snapshot().inflight, "the reader still holds raw admission")
            assertEquals(
                0,
                provider.ended.get(),
                "request heap/provider cleanup must not run at a local tool step",
            )
            upstream.release.countDown()
            val raw = withTimeout(5_000) { checkNotNull(interceptor.reading).await() }
            assertTrue(
                raw is TurnOutcome.Success,
                "first-client loss must not abandon the live reader: $raw",
            )
            assertEquals(7L, (raw as TurnOutcome.Success).usage.outputTokens)
            withTimeout(5_000) { while (gate.snapshot().inflight != 0) yield() }
            assertEquals(1, provider.ended.get())
            assertEquals(1, upstream.posts.get())
            assertEquals(7L, deps.stores.usageStore.readState().outputTokens5h)
        } finally {
            upstream.release.countDown()
            head.stop()
            client.close()
            upstream.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(20)
    fun `the WS source job outlives its client and supervises its own cancellation`(
        cancel: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val runner = GatedSourceRunner(if (cancel) IllegalStateException("synthetic private abort bytes") else null)
        val delegate = base("http://127.0.0.1:9/responses")
        val interceptor = SourceInterceptor()
        val provider = SourceProvider(
            object : Provider by delegate {
                override val wsRunner: WsRoundRunner = runner
            },
            interceptor,
        )
        val gate = InflightGate({ 1 })
        val head = HeadServer(provider, 0, headDeps(tmp, gate = gate))
        val client = HttpClient(CIO)
        head.start()
        try {
            val body = sourceStep(client, head.port, "ws-source-owner", """[{"role":"user","content":"go"}]""")
            assertTrue(body.contains("tool_use"))
            assertTrue(body.contains("message_stop"))
            client.close()
            assertEquals(0, runner.aborts, "ending the first client step must not abort the source WS round")
            assertEquals(1, gate.snapshot().inflight)
            assertEquals(0, provider.ended.get())
            val reading = checkNotNull(interceptor.reading)
            if (cancel) {
                assertDoesNotThrow { reading.cancel() }
                reading.join()
                assertTrue(reading.isCancelled)
                assertTrue(interceptor.completionFailures.isEmpty(), interceptor.completionFailures.toString())
            } else {
                runner.release.complete(Unit)
                val outcome = withTimeout(5_000) { reading.await() }
                assertTrue(outcome is TurnOutcome.Success, outcome.toString())
                assertEquals(7L, (outcome as TurnOutcome.Success).usage.outputTokens)
            }
            withTimeout(5_000) { while (gate.snapshot().inflight != 0) yield() }
            assertEquals(if (cancel) 1 else 0, runner.aborts)
            assertEquals(1, runner.posts)
        } finally {
            runner.release.complete(Unit)
            head.stop()
            client.close()
        }
    }

    @Test
    @Timeout(20)
    fun `a source cancelled after its WS terminal still bills after its successful client step`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val runner = GatedSourceRunner(ending = SourceEnding.CANCEL_AFTER_TERMINAL)
        val interceptor = SourceInterceptor()
        val provider = SourceProvider(
            object : Provider by base("http://127.0.0.1:9/responses") {
                override val wsRunner: WsRoundRunner = runner
            },
            interceptor,
        )
        val gate = InflightGate({ 1 })
        val deps = headDeps(tmp, gate = gate)
        val head = HeadServer(provider, 0, deps)
        val client = HttpClient(CIO)
        head.start()
        try {
            val body = sourceStep(client, head.port, "late-cancel-source", """[{"role":"user","content":"go"}]""")
            assertTrue(body.contains("tool_use"))
            assertTrue(body.contains("message_stop"), "the client step finished before source accounting")
            client.close()
            assertEquals(1, gate.snapshot().inflight, "the raw source is still reading")
            runner.release.complete(Unit)
            val reading = checkNotNull(interceptor.reading)
            withTimeout(5_000) {
                reading.join()
                while (gate.snapshot().inflight != 0 || provider.ended.get() != 1) yield()
            }
            assertTrue(reading.isCancelled, "terminal accounting does not revoke the real source cancellation")
            assertEquals(7L, deps.stores.usageStore.readState().outputTokens5h, "the received terminal is billed once")
            assertEquals(1, runner.posts, "completed source is never rerun to recover its tokens")
            assertEquals(1, runner.aborts)
        } finally {
            runner.release.complete(Unit)
            head.stop()
            client.close()
        }
    }

    private enum class SourceEnding { NORMAL, CANCEL_AFTER_TERMINAL }

    private class GatedSourceRunner(
        private val abortFailure: RuntimeException? = null,
        private val ending: SourceEnding = SourceEnding.NORMAL,
    ) : WsRoundRunner {
        val release = CompletableDeferred<Unit>()
        var posts = 0
        var aborts = 0
        override suspend fun attempt(
            bodyJson: String,
            meta: TurnMeta,
            turnHeaders: Map<String, String>,
            creds: Credentials,
        ): WsRound {
            posts++
            return WsRound(
                flow {
                    emit(
                        Json.parseToJsonElement(
                            """{"type":"response.output_item.added","output_index":0,"item":{"type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
                        ).jsonObject,
                    )
                    emit(
                        Json.parseToJsonElement(
                            """{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":"await tools.Read({});"}""",
                        ).jsonObject,
                    )
                    release.await()
                    emit(
                        Json.parseToJsonElement(
                            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":7},"output":[{"type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":"await tools.Read({});"}]}}""",
                        ).jsonObject,
                    )
                    if (ending == SourceEnding.CANCEL_AFTER_TERMINAL) currentCoroutineContext().cancel()
                },
                WsRoundAbort {
                    aborts++
                    release.completeExceptionally(java.io.IOException("synthetic WS source aborted"))
                    abortFailure?.let { throw it }
                },
            )
        }
        override fun isFailureTerminal(event: kotlinx.serialization.json.JsonObject): Boolean = false
        override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit
        override fun roundBypassed(meta: TurnMeta) = Unit
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    @Timeout(20)
    fun `source-result continuations adopt held slots even when every permit is occupied`(
        count: Int,
        @TempDir tmp: Path,
    ) =
        runBlocking {
            val upstream = GatedSourceServer()
            val provider = SourceProvider(base(upstream.url))
            val gate = InflightGate({ count })
            val head = HeadServer(provider, 0, headDeps(tmp, gate = gate))
            val client = HttpClient(CIO)
            head.start()
            try {
                val sessions = List(count) { "source-session-$it" }
                for (session in sessions) {
                    val first = sourceStep(client, head.port, session, """[{"role":"user","content":"go"}]""")
                    assertTrue(first.contains("tool_use"))
                }
                for (session in sessions) {
                    val next = withTimeout(3_000) {
                        sourceStep(
                            client,
                            head.port,
                            session,
                            """[{"role":"user","content":"go"},
                                {"role":"assistant","content":[{"type":"tool_use","id":"test-client-call",
                                "name":"Read","input":{}}]},
                                {"role":"user","content":[{"type":"tool_result","tool_use_id":"test-client-call",
                                "content":"done"}]}]""",
                        )
                    }
                    assertTrue(next.contains("message_stop"))
                }
                assertEquals(count, upstream.posts.get())
                assertEquals(count, gate.snapshot().inflight)
                assertEquals(count.toLong(), gate.snapshot().acquired)
            } finally {
                upstream.release.countDown()
                head.stop()
                client.close()
                upstream.close()
            }
        }

    @Test
    @Timeout(20)
    fun `operator stop cancels the retained raw reader after its first client step`(@TempDir tmp: Path) = runBlocking {
        val upstream = GatedSourceServer()
        val interceptor = SourceInterceptor()
        val provider = SourceProvider(base(upstream.url), interceptor)
        val gate = InflightGate({ 1 })
        val deps = headDeps(tmp, gate = gate)
        val head = HeadServer(provider, 0, deps)
        val client = HttpClient(CIO)
        head.start()
        try {
            val first = sourceStep(client, head.port, "source-stop", """[{"role":"user","content":"go"}]""")
            assertTrue(first.contains("tool_use"))
            val live = deps.liveTurns.list().single()
            assertTrue(deps.liveTurns.stop(live.id) != null)
            withTimeout(3_000) { while (gate.snapshot().inflight != 0 || provider.ended.get() != 1) yield() }
            assertTrue(checkNotNull(interceptor.reading).isCancelled)
            assertEquals(1, provider.ended.get())
            assertEquals(1, upstream.posts.get())
        } finally {
            upstream.release.countDown()
            head.stop()
            client.close()
            upstream.close()
        }
    }

    @Test
    @Timeout(20)
    fun `the raw round total cap survives completion of its first client step`(@TempDir tmp: Path) = runBlocking {
        val upstream = GatedSourceServer()
        val interceptor = SourceInterceptor()
        val provider = SourceProvider(base(upstream.url, WatchdogBudget(5.seconds, 5.seconds, 1.seconds)), interceptor)
        val gate = InflightGate({ 1 })
        val head = HeadServer(provider, 0, headDeps(tmp, gate = gate))
        val client = HttpClient(CIO)
        head.start()
        try {
            val first = sourceStep(client, head.port, "source-cap", """[{"role":"user","content":"go"}]""")
            assertTrue(first.contains("tool_use"))
            val reader = checkNotNull(interceptor.reading)
            withTimeout(5_000) {
                while (!reader.isCompleted) yield()
                while (gate.snapshot().inflight != 0 || provider.ended.get() != 1) yield()
            }
            assertTrue(reader.isCancelled, "the original raw cap must cancel the independently owned reader")
            assertEquals(1, provider.ended.get())
            assertEquals(1, upstream.posts.get())
        } finally {
            upstream.release.countDown()
            head.stop()
            client.close()
            upstream.close()
        }
    }

    private suspend fun sourceStep(client: HttpClient, port: Int, session: String, messages: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", session)
            setBody(
                """{"model":"claude-codex--stream-test","stream":true,"max_tokens":64,"messages":$messages}""",
            )
        }.bodyAsText()

    private fun base(
        url: String,
        watchdog: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 15.seconds),
    ): Provider = TestResponsesProvider(
        ProviderTuning(
            key = "source-test",
            label = "source-test",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("stream-test", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "stream-test",
            auth = SourceAuth(),
            baseUrl = url,
            watchdog = watchdog,
        ),
        ReasoningDisplay.TEXT,
        false,
        null,
        null,
    )

    private class SourceAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
    }

    private class SourceProvider(
        private val base: Provider,
        private val interceptor: SourceInterceptor? = null,
    ) : Provider by base {
        private val readers = java.util.concurrent.ConcurrentHashMap<String, SourceInterceptor>()
        val ended = AtomicInteger()
        override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
            base.buildTurn(body, compact, sessionId).copy(
                roundInterceptor = interceptor ?: readers.computeIfAbsent(checkNotNull(sessionId)) { SourceInterceptor() },
                onEnd = { ended.incrementAndGet() },
            )
        override fun onHeadStop() {
            interceptor?.stop()
            readers.values.forEach(SourceInterceptor::stop)
        }
    }

    private class SourceInterceptor : RoundInterceptor {
        val completionFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        private val scope = LifecycleScope(
            ProcessDispatchers().io() + CoroutineExceptionHandler { _, failure -> completionFailures.add(failure) },
        )
        private val first = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val observed: CompletableDeferred<Unit> get() = first
        var reading: Deferred<TurnOutcome>? = null
        override fun resumesSource(): Boolean = first.isCompleted && reading?.isActive == true
        override suspend fun intercept(
            bodyJson: String,
            sink: WireSink,
            postRound: InterceptedRoundPost,
        ): TurnOutcome {
            if (first.isCompleted) {
                return TurnOutcome.Success(false, false, Usage(localStep = true), messageClosed = true)
            }
            entered.complete(Unit)
            val redirected = postRound as RedirectableRoundPost
            val reader = Reader(first)
            reading = scope.async {
                reader.ownerScope = this
                redirected.into(bodyJson, reader)
            }
            first.await()
            val tool = sink.openTool("test-client-call", "Read")
            sink.inputJsonDelta(tool, "{}")
            sink.closeBlock(tool)
            return TurnOutcome.Success(true, false, Usage(localStep = true))
        }
        fun stop() {
            scope.coroutineContext.cancelChildren()
        }
    }

    private class Reader(private val first: CompletableDeferred<Unit>) :
        IndependentRoundSink, WireSink by RecordingSink2() {
        override lateinit var ownerScope: CoroutineScope
        override suspend fun closeAll() {
            currentCoroutineContext().ensureActive()
        }
        override suspend fun customToolSource(event: CustomToolSource) {
            if (event is CustomToolSource.Delta) first.complete(Unit)
        }
    }

    private class GatedSourceServer {
        val release = CountDownLatch(1)
        val posts = AtomicInteger()
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = pool
            server.createContext("/responses", ::respond)
            server.start()
        }
        private fun respond(exchange: HttpExchange) {
            posts.incrementAndGet()
            exchange.requestBody.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                event(
                    output,
                    """{"type":"response.output_item.added","output_index":0,"item":{
                        "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
                )
                event(
                    output,
                    """{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":"await tools.Read({});"}""",
                )
                release.await()
                event(
                    output,
                    """{"type":"response.completed","response":{"status":"completed",
                        "usage":{"input_tokens":100,"output_tokens":7},"output":[{
                        "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
                        "input":"await tools.Read({});"}]}}""",
                )
            }
        }
        private fun event(output: java.io.OutputStream, json: String) {
            output.write(("data: " + json.lineSequence().joinToString("") + "\n\n").toByteArray())
            output.flush()
        }

        fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }
}
