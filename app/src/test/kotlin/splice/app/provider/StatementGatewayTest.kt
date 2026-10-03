package splice.app.provider

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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.DEFAULT_WORKER_START_TIMEOUT_MS
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class StatementGatewayTest {
    @Test
    @Timeout(60)
    fun `a real result request waits with a signed notice for the same raw round's next statement`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val gateway = Gateway(tmp, this)
        try {
            val calls = gateway.firstStep()
            rejectUnderCpuLoad(gateway)
            gateway.nextStatement(calls)
            gateway.finish()
        } finally {
            gateway.close()
        }
    }

    @Test
    @Timeout(60)
    fun `real native Promise all batches resume on the held source before completion`(
        @TempDir tmp: Path,
    ) = runBlocking {
        nativeBatch(tmp, this, "all")
    }

    @Test
    @Timeout(60)
    fun `real native Promise allSettled batches resume on the held source before completion`(
        @TempDir tmp: Path,
    ) = runBlocking {
        nativeBatch(tmp, this, "allSettled")
    }

    @Test
    @Timeout(60)
    fun `removing producer batch admission holds both native batches until upstream completion`(
        @TempDir tmp: Path,
    ) = runBlocking {
        for (batch in listOf("all", "allSettled")) {
            val gateway = Gateway(tmp.resolve(batch), this, batch, excludeBatchAdmission = true)
            try {
                gateway.blockedBatch()
            } finally {
                gateway.close()
            }
        }
    }

    /** Stress the publication boundary only after native startup, without changing its deadlines. */
    private suspend fun rejectUnderCpuLoad(gateway: Gateway) {
        val active = AtomicBoolean(true)
        val started = CountDownLatch(2)
        val work = AtomicLong()
        val workers = mutableListOf<Thread>()
        try {
            repeat(2) {
                val worker = Thread(
                    {
                        started.countDown()
                        var value = 1L
                        while (active.get()) {
                            repeat(4096) { value = value * 1_664_525L + 1_013_904_223L }
                            work.addAndGet(value)
                        }
                    },
                    "synthetic-lease-pressure",
                )
                workers += worker
                worker.priority = Thread.MIN_PRIORITY
                worker.start()
            }
            started.await()
            gateway.rejectUnrelated()
        } finally {
            active.set(false)
            workers.forEach(Thread::join)
        }
    }

    private suspend fun nativeBatch(tmp: Path, scope: CoroutineScope, batch: String) {
        val gateway = Gateway(tmp, scope, batch)
        try {
            val first = gateway.firstStep()
            gateway.rejectUnrelated()
            val next = gateway.nextStatement(first)
            assertEquals(4, (first + next).map { it.getValue("id") }.distinct().size)
            gateway.completeBatch(next)
        } finally {
            gateway.close()
        }
    }

    private inner class Gateway(
        tmp: Path,
        private val scope: CoroutineScope,
        private val batch: String? = null,
        excludeBatchAdmission: Boolean = false,
    ) {
        private val upstream = StatementGatewayUpstream(batch)
        private val runtime = StatementGatewayRuntime(excludeBatchAdmission)
        private val history = mutableListOf(Json.parseToJsonElement("""{"role":"user","content":"go"}""").jsonObject)
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

        // Both result trees remain owned by the source slot; measured batch weights total 16,713 bytes.
        private val heapBudget = firstWeight + if (batch == null) 6_500L else 20_000L
        private val retainedWeights = mutableListOf(firstWeight)
        private val heap = RequestMaterializationGate(heapBudgetBytes = heapBudget)
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

        suspend fun firstStep(): List<JsonObject> {
            head.start()
            val first = withTimeout(WORKER_START_BOUND_MS) { send(firstBody).bodyAsText() }
            assertTrue(first.contains("message_stop"), first)
            assertScriptShownAsNotice(first, "tools.Read")
            assertEquals(1L, upstream.terminal.count)
            return toolCalls(first).also { calls ->
                assertEquals(if (batch == null) 1 else 2, calls.size, first)
                assertTrue(calls.all { it.getValue("name").jsonPrimitive.content == "Read" }, first)
                assertEquals(529, heapProbe(14_000), "the held raw source retains its materialized request")
            }
        }

        suspend fun rejectUnrelated() {
            assertEquals(400, send("{").status.value, "local preparation needs no fresh upstream permit")
            assertEquals(200, heapProbe(1_000), "local preparation releases its heap lease")
            waiting = scope.async { gate.acquire() }
            withTimeout(3_000) { while (gate.snapshot().queued != 1) yield() }
            val unrelated = send(body("""[{"role":"user","content":"a different request"}]"""))
            assertEquals(529, unrelated.status.value, "a matching header cannot authorize unrelated upstream work")
            assertEquals(200, heapProbe(1_000), "refused admission releases its heap lease")
            assertEquals(1, upstream.posts.get())
        }

        suspend fun nextStatement(calls: List<JsonObject>): List<JsonObject> = coroutineScope {
            val notice = CompletableDeferred<Unit>()
            val resumed = async { resultRequest(calls, notice) }
            assertEquals(
                List(calls.size) { "read-result" },
                withTimeout(5_000) { runtime.delivered.receive() }.map { it.output },
            )
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
            assertScriptShownAsNotice(next, "tools.Edit")
            assertTrue(next.contains("message_stop"), next)
            assertEquals(1L, upstream.terminal.count, "the callback precedes response.completed")
            assertEquals(1, upstream.posts.get())
            assertEquals(1, runtime.starts.get())
            assertEquals(0L, deps.stores.usageStore.readState().outputTokens5h)
            assertEquals(1, deps.liveTurns.list().size, "adoption must not duplicate the live row")
            toolCalls(next).also { emitted -> assertEquals(calls.size, emitted.size, next) }
        }

        private suspend fun resultRequest(calls: List<JsonObject>, notice: CompletableDeferred<Unit>): String {
            val wire = StringBuilder()
            client.preparePost(url) {
                bearerAuth("test-inference-token")
                headers.append("x-claude-code-session-id", "statement-session")
                setBody(resultBody(calls))
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
            awaitReleased(7L)
        }

        suspend fun completeBatch(calls: List<JsonObject>) = coroutineScope {
            checkNotNull(waiting).cancelAndJoin()
            val request = resultBody(calls)
            assertTrue(
                retainedWeights.sum() <= heapBudget,
                "The fixture must admit its retained bodies: weights=$retainedWeights budget=$heapBudget",
            )
            val finished = async { send(request).bodyAsText() }
            assertEquals(
                List(calls.size) { "edit-result" },
                withTimeout(5_000) { runtime.delivered.receive() }.map { it.output },
            )
            assertFalse(finished.isCompleted, "the native cell cannot finish before source completion")
            assertEquals(1L, upstream.terminal.count)
            assertEquals(1, upstream.posts.get(), "both batches resume the original source POST")
            upstream.terminal.countDown()
            val answer = withTimeout(5_000) { finished.await() }
            assertTrue(answer.contains("native batch complete"), answer)
            assertTrue(answer.contains("message_stop"), answer)
            assertTrue(answer.contains("end_turn"), answer)
            val complete = withTimeout(5_000) { runtime.completed.await() }
            assertNull(complete.error)
            assertTrue(complete.output.contains("native batch finished"), complete.output)
            assertEquals(listOf("Read", "Read", "Edit", "Edit"), runtime.calls.map { it.name })
            assertEquals(4, runtime.calls.map { it.id }.distinct().size)
            assertEquals(1, runtime.starts.get())
            assertEquals(1L, gate.snapshot().acquired)
            assertEquals(2, upstream.posts.get(), "one source POST plus one normal exec-result continuation")
            val input = Json.parseToJsonElement(upstream.requests.last()).jsonObject.getValue("input").jsonArray
            val result = input.map { it.jsonObject }
                .single { it["type"]?.jsonPrimitive?.content == "custom_tool_call_output" }
            assertEquals("source-call", result.getValue("call_id").jsonPrimitive.content)
            assertTrue(result.getValue("output").jsonPrimitive.content.contains("native batch finished"))
            awaitReleased(10L)
        }

        suspend fun blockedBatch() = coroutineScope {
            head.start()
            val pending = async { send(firstBody).bodyAsText() }
            try {
                withTimeout(WORKER_START_BOUND_MS) { runtime.started.await() }
                assertNull(withTimeoutOrNull(250) { pending.await() }, "removing admission must block early dispatch")
                assertEquals(1L, upstream.terminal.count)
                assertEquals(1, upstream.posts.get())
                upstream.next.countDown()
                assertNull(withTimeoutOrNull(250) { pending.await() }, "a later statement without EOF must not release")
                upstream.terminal.countDown()
                val wire = withTimeout(5_000) { pending.await() }
                assertEquals(2, toolCalls(wire).size, wire)
                assertEquals(1, runtime.starts.get())
            } finally {
                pending.cancelAndJoin()
            }
        }

        private suspend fun awaitReleased(outputTokens: Long) {
            withTimeout(5_000) {
                while (gate.snapshot().inflight != 0) yield()
                while (deps.stores.usageStore.readState().outputTokens5h != outputTokens) yield()
                // The permit is counted down before its release callbacks finish.
                while (heapProbe(14_000) != 200) yield()
                while (deps.liveTurns.list().isNotEmpty()) yield()
            }
            assertTrue(deps.liveTurns.list().isEmpty())
        }

        private fun resultBody(calls: List<JsonObject>): String {
            history += buildJsonObject {
                put("role", "assistant")
                put("content", JsonArray(calls))
            }
            val results = calls.map { call ->
                val output = if (call.getValue("name").jsonPrimitive.content == "Read") "read-result" else "edit-result"
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", call.getValue("id"))
                    put("content", output)
                }
            }
            history += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(results))
            }
            return body(JsonArray(history).toString()).also { request ->
                retainedWeights += (request.toByteArray().size * 13L + 1L) / 2L
            }
        }

        private suspend fun heapProbe(bodyBytes: Int): Int {
            val empty = body("""[{"role":"user","content":""}]""")
            val text = "x".repeat((bodyBytes - empty.toByteArray().size).coerceAtLeast(0))
            return client.post("$url/count_tokens") {
                bearerAuth("test-inference-token")
                setBody(body("""[{"role":"user","content":"$text"}]"""))
            }.status.value
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
            try {
                head.stop()
            } finally {
                runtime.close()
                client.close()
                ticks.close()
                upstream.close()
            }
        }
    }

    private fun events(wire: String): List<JsonObject> = wire.lineSequence().filter { it.startsWith("data: ") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()

    private class WireBlock(val type: String) {
        val content = StringBuilder()
        val signatures = mutableListOf<String>()

        fun append(fields: JsonObject, keys: List<String>) = keys.forEach { key ->
            fields[key]?.let { content.append(it.jsonPrimitive.content) }
        }
    }

    /** Every content block on the wire, with its streamed content and signatures, in close order. */
    private fun blocks(wire: String): List<WireBlock> {
        val open = linkedMapOf<String, WireBlock>()
        val closed = mutableListOf<WireBlock>()
        for (event in events(wire)) {
            val index = event["index"]?.jsonPrimitive?.content ?: continue
            when (event["type"]?.jsonPrimitive?.content) {
                "content_block_start" -> open[index] = startBlock(event.getValue("content_block").jsonObject)
                "content_block_delta" -> deltaBlock(open.getValue(index), event.getValue("delta").jsonObject)
                "content_block_stop" -> open.remove(index)?.let { closed += it }
            }
        }
        return closed + open.values
    }

    private fun startBlock(block: JsonObject): WireBlock =
        WireBlock(block.getValue("type").jsonPrimitive.content).also { it.append(block, listOf("text", "thinking")) }

    private fun deltaBlock(block: WireBlock, delta: JsonObject) {
        delta["signature"]?.let { block.signatures += it.jsonPrimitive.content }
        block.append(delta, listOf("text", "thinking", "partial_json"))
    }

    /** V4-456: the client sees the live script while the model writes it, and only inside thinking blocks
     *  carrying splice's notice signature, which every request parser drops: never as text, a tool input or
     *  reasoning the client would replay. */
    private fun assertScriptShownAsNotice(wire: String, statement: String) {
        val script = blocks(wire).filter { it.content.contains("tools.") }
        assertTrue(script.any { it.content.contains(statement) }, wire)
        assertTrue(script.all { it.type == "thinking" && it.signatures == listOf(SpliceNotice.SIGNATURE) }, wire)
    }

    private fun toolCalls(wire: String): List<JsonObject> {
        val events = events(wire)
        val calls = events.filter { it["type"]?.jsonPrimitive?.content == "content_block_start" }
            .filter { it.getValue("content_block").jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
            .associate { it.getValue("index").jsonPrimitive.content to it.getValue("content_block").jsonObject }
        val arguments = events.filter { it["type"]?.jsonPrimitive?.content == "content_block_delta" }
            .filter { it.getValue("delta").jsonObject["type"]?.jsonPrimitive?.content == "input_json_delta" }
            .groupBy { it.getValue("index").jsonPrimitive.content }
        return calls.map { (index, call) ->
            val text = arguments[index].orEmpty().joinToString("") {
                it.getValue("delta").jsonObject.getValue("partial_json").jsonPrimitive.content
            }
            val restored = call.toMutableMap()
            if (text.isNotEmpty()) restored["input"] = Json.parseToJsonElement(text)
            JsonObject(restored)
        }
    }

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

// why: the first step boots a real code-mode worker JVM, which the product allows 30 s to start; a 5 s bound
// failed under CI compile load (run 37112725473). The worker's own bound plus 5 s still catches a hang.
private const val WORKER_START_BOUND_MS: Long = DEFAULT_WORKER_START_TIMEOUT_MS + 5_000L
