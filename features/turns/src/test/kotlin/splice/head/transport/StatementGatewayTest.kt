package splice.head.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SpliceNotice
import splice.core.turn.WatchdogBudget
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.admission.RequestMaterializationGate
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import splice.upstream.Ticker
import splice.upstream.retry.InflightGate
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class StatementGatewayTest {
    @Test
    @Timeout(30)
    fun `a real result request waits with a signed notice for the same raw round's next statement`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val gateway = Gateway(tmp, this)
        try {
            val id = gateway.firstStep()
            gateway.rejectUnrelated()
            gateway.nextStatement(id)
            gateway.finish()
        } finally {
            gateway.close()
        }
    }

    private inner class Gateway(tmp: Path, private val scope: CoroutineScope) {
        private val upstream = StatementGatewayUpstream()
        private val runtime = StatementGatewayRuntime()
        private val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        private val ticks = Channel<Unit>(Channel.UNLIMITED)
        private val gate = InflightGate({ 1 }, maxQueued = { 1 })
        private val firstBody = body("""[{"role":"user","content":"go"}]""")
        private val firstWeight = (firstBody.toByteArray().size * 13L + 1L) / 2L
        private val heap = RequestMaterializationGate(heapBudgetBytes = firstWeight + 6_500L)
        private var waiting: Deferred<InflightGate.Admission>? = null
        private val deps = headDeps(
            tmp,
            gate = gate,
            seams = HeadDeps.HeadSeams(
                ticker = Ticker {
                    ticks.receive()
                    true
                },
                requestMaterializationGate = heap,
            ),
        )
        private val head = HeadServer(provider(upstream.url, bridge), 0, deps)
        private val client = HttpClient(CIO)
        private val url: String get() = "http://127.0.0.1:${head.port}/v1/messages"

        suspend fun firstStep(): String {
            head.start()
            val first = withTimeout(5_000) { send(firstBody).bodyAsText() }
            assertTrue(first.contains("message_stop"))
            assertFalse(first.contains("await tools."))
            assertEquals(1L, upstream.terminal.count)
            return toolId(first)
        }

        suspend fun rejectUnrelated() {
            assertEquals(400, send("{").status.value, "local preparation needs no fresh upstream permit")
            assertNotNull(heap.tryWithLease(1_000) { "spare" }, "local preparation releases its heap lease")
            waiting = scope.async { gate.acquire() }
            withTimeout(3_000) { while (gate.snapshot().queued != 1) yield() }
            val unrelated = send(body("""[{"role":"user","content":"a different request"}]"""))
            assertEquals(529, unrelated.status.value, "a matching header cannot authorize unrelated upstream work")
            assertNotNull(heap.tryWithLease(1_000) { "spare" }, "refused admission releases its heap lease")
            assertEquals(1, upstream.posts.get())
        }

        suspend fun nextStatement(id: String) = coroutineScope {
            val notice = CompletableDeferred<Unit>()
            val resumed = async { resultRequest(id, notice) }
            assertEquals("read-result", withTimeout(5_000) { runtime.delivered.await() }.single().output)
            repeat(30) { ticks.send(Unit) }
            withTimeout(5_000) { notice.await() }
            assertFalse(resumed.isCompleted, "the result request waits for a certified statement")
            assertEquals(1, upstream.posts.get())
            assertEquals(1L, gate.snapshot().acquired)
            assertEquals(1L, upstream.next.count)
            upstream.next.countDown()
            val next = withTimeout(5_000) { resumed.await() }
            assertTrue(next.contains("\"name\":\"Edit\""), next)
            assertTrue(next.contains(SpliceNotice.SIGNATURE), next)
            assertTrue(next.indexOf("signature_delta") < next.indexOf("\"name\":\"Edit\""), next)
            assertFalse(next.contains("await tools."), next)
            assertTrue(next.contains("message_stop"), next)
            assertEquals(1L, upstream.terminal.count, "the callback precedes response.completed")
            assertEquals(1, upstream.posts.get())
            assertEquals(1, runtime.starts.get())
            assertEquals(0L, deps.stores.usageStore.readState().outputTokens5h)
            assertEquals(1, deps.liveTurns.list().size, "adoption must not duplicate the live row")
        }

        private suspend fun resultRequest(id: String, notice: CompletableDeferred<Unit>): String {
            val wire = StringBuilder()
            client.preparePost(url) {
                bearerAuth("test-inference-token")
                headers.append("x-claude-code-session-id", "statement-session")
                setBody(
                    body(
                        """[{"role":"user","content":"go"},
                            {"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"Read","input":{}}]},
                            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"read-result"}]}]""",
                    ),
                )
            }.execute { response ->
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readLine() ?: break
                    wire.append(line).append('\n')
                    if (line.contains("[splice] holding this turn open.")) notice.complete(Unit)
                }
            }
            return wire.toString()
        }

        suspend fun finish() {
            checkNotNull(waiting).cancelAndJoin()
            upstream.terminal.countDown()
            withTimeout(5_000) {
                while (gate.snapshot().inflight != 0 || deps.stores.usageStore.readState().outputTokens5h != 7L) yield()
            }
            withTimeout(5_000) {
                // The permit is counted down before its release callbacks finish.
                while (heap.tryWithLease(Long.MAX_VALUE) { "all free" } == null) yield()
                while (deps.liveTurns.list().isNotEmpty()) yield()
            }
            assertTrue(deps.liveTurns.list().isEmpty())
        }

        private suspend fun send(request: String) = client.post(url) {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", "statement-session")
            setBody(request)
        }

        suspend fun close() {
            waiting?.cancel()
            upstream.next.countDown()
            upstream.terminal.countDown()
            head.stop()
            client.close()
            ticks.close()
            upstream.close()
        }
    }

    private fun toolId(wire: String): String = wire.lineSequence()
        .filter { it.startsWith("data: ") && it.contains("\"type\":\"tool_use\"") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject.getValue("content_block").jsonObject }
        .single().getValue("id").jsonPrimitive.content

    private fun body(messages: String): String =
        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,"messages":$messages,
            "tools":[{"name":"Read","description":"Read a synthetic fixture","input_schema":{"type":"object"}},
                {"name":"Edit","description":"Edit a synthetic fixture","input_schema":{"type":"object"}}]}"""

    private fun provider(url: String, bridge: CodexCodeModeBridge): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "source-test",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            },
            baseUrl = url,
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 20.seconds),
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = null,
        configSummary = null,
        codeModeBridge = bridge,
        codeModeModels = listOf("gpt-5.6-sol"),
        log = {},
    )
}
