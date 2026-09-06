// NEW: real worker deadlines and UTF-8 boundaries are verified through the Codex bridge.
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.app.codemode.JvmCodeModeRuntime
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import splice.spi.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val BRIDGE_BASE_REQUEST = """{"input":[{"role":"developer","content":"s"}]}"""

@Timeout(15)
class CodeModeBridgeRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))
    private val upstreamUsage = Usage(19, 7, 5, 3)

    @Test
    fun `startup deadline is an ordinary bridge failure preserving upstream usage`() = runBlocking {
        runtime(timeoutMs = 1_000).use { runtime ->
            val bridge = bridge(runtime)
            val source = "/* private source marker */ while (true) {}"
            val outcome = bridge.interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, Sink()) { outer(source) }
            assertTrue(outcome is TurnOutcome.Failure)
            val failure = outcome as TurnOutcome.Failure
            assertEquals(upstreamUsage, failure.salvagedUsage)
            assertTrue(failure.message.contains("timed out"))
            assertFalse(failure.message.contains("private source marker"))
            assertFalse(failure.message.contains("cancelled"))
            val replacement = runtime.start("return 'reaped';", emptySet())
            assertEquals("reaped", (replacement.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `advance deadline is an ordinary bridge failure and does not replay source`() = runBlocking {
        runtime(timeoutMs = 2_000).use { runtime ->
            val bridge = bridge(runtime)
            val sink = Sink()
            val first = bridge.interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, sink) {
                    outer("await tools.call('Read', {}); while (true) {}")
                }
            assertEquals(upstreamUsage, (first as TurnOutcome.Success).usage)
            val id = sink.ids.single()
            val output = "private result marker"
            val failure = bridge.interceptor(turn(id, output), disableParallel = false)
                .intercept(requestWithResult(id, output), Sink()) { error("must not post upstream") }
            assertTrue(failure is TurnOutcome.Failure)
            assertTrue((failure as TurnOutcome.Failure).message.contains("timed out"))
            assertFalse(failure.message.contains(output))
            assertFalse(failure.message.contains("cancelled"))
            val replacement = runtime.start("return 'reaped';", emptySet())
            assertEquals("reaped", (replacement.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `parent cancellation through bridge stays cancellation and reaps worker`() = runBlocking {
        runtime(timeoutMs = 10_000).use { runtime ->
            val bridge = bridge(runtime)
            val before = ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList() }
            val startup = async {
                bridge.interceptor(turn(), disableParallel = false)
                    .intercept(BRIDGE_BASE_REQUEST, Sink()) { outer("while (true) {}") }
            }
            val child = withTimeout(2_000) {
                var spawned: ProcessHandle? = null
                while (spawned == null) {
                    yield()
                    spawned = ProcessHandle.current().children().use { children ->
                        children.filter { it.pid() !in before }.findFirst().orElse(null)
                    }
                }
                spawned
            }
            startup.cancelAndJoin()
            assertThrows(CancellationException::class.java) { runBlocking { startup.await() } }
            child.onExit().get(1, TimeUnit.SECONDS)
            assertFalse(child.isAlive)
            val replacement = runtime.start("return 'reaped';", emptySet())
            assertEquals("reaped", (replacement.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `bridge rejects oversized UTF8 source before allocating a worker`() = runBlocking {
        runtime().use { runtime ->
            val occupied = runtime.start("await tools.call('Read', {});", setOf("Read"))
            val source = "//" + "é".repeat(32_768)
            val failure = bridge(runtime).interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, Sink()) { outer(source) } as TurnOutcome.Failure
            assertTrue(failure.message.contains("source exceeds"), failure.message)
            assertEquals(upstreamUsage, failure.salvagedUsage)
            assertTrue(occupied.advance() is CodeModeStep.Calls)
            occupied.close()
        }
    }

    @Test
    fun `bridge accepts exact UTF8 source boundary`() = runBlocking {
        runtime().use { runtime ->
            val prefix = "return 'ok'; // "
            val source = prefix + "é".repeat((65_536 - prefix.length) / 2)
            assertEquals(65_536, source.encodeToByteArray().size)
            var posts = 0
            val outcome = bridge(runtime).interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, Sink()) {
                    if (++posts == 1) {
                        outer(source)
                    } else {
                        TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                    }
                }
            assertTrue(outcome is TurnOutcome.Success)
            assertEquals(2, posts)
        }
    }

    @Test
    fun `bridge result UTF8 boundary matches the real worker`() = runBlocking {
        for (bytes in listOf(65_536, 65_538)) {
            runtime().use { runtime ->
                val bridge = bridge(runtime, "result-$bytes.json")
                val sink = Sink()
                bridge.interceptor(turn(), disableParallel = false)
                    .intercept(BRIDGE_BASE_REQUEST, sink) { outer("return await tools.call('Read', {});") }
                val id = sink.ids.single()
                val output = "é".repeat(bytes / 2)
                var posts = 0
                val outcome = bridge.interceptor(turn(id, output), disableParallel = false)
                    .intercept(requestWithResult(id, output), Sink()) {
                        posts++
                        TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                    }
                if (bytes == 65_536) {
                    assertTrue(outcome is TurnOutcome.Success)
                    assertEquals(1, posts)
                } else {
                    assertTrue(outcome is TurnOutcome.Failure)
                    assertEquals(
                        "code-mode tool result '$id' exceeds the size limit",
                        (outcome as TurnOutcome.Failure).message,
                    )
                    assertEquals(0, posts)
                    val corrected = bridge.interceptor(turn(id, "corrected"), disableParallel = false)
                        .intercept(requestWithResult(id, "corrected"), Sink()) {
                            posts++
                            TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                        }
                    assertTrue(corrected is TurnOutcome.Success)
                    assertEquals(1, posts)
                }
            }
        }
    }

    @Test
    fun `oversized escaped parallel frame leaves results unconsumed and corrected retry works`() = runBlocking {
        runtime().use { runtime ->
            val bridge = bridge(runtime)
            val sink = Sink()
            bridge.interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, sink) { outer(threeCallsSource()) }
            assertEquals(3, sink.ids.size)
            val oversized = sink.ids.map { CodeModeResult(it, 0.toChar().toString().repeat(65_536)) }
            val stateBefore = Files.readString(tempDir.resolve("bridge.json"))
            val rejected = bridge.interceptor(turn().copy(toolResults = oversized), disableParallel = false)
                .intercept(requestWithResults(oversized), Sink()) { error("must not post oversized results") }
            assertTrue(rejected is TurnOutcome.Failure)
            assertEquals(ErrorType.INVALID_REQUEST, (rejected as TurnOutcome.Failure).type)
            assertEquals("code-mode result frame exceeds the size limit", rejected.message)
            assertEquals(stateBefore, Files.readString(tempDir.resolve("bridge.json")))

            val corrected = sink.ids.map { CodeModeResult(it, "corrected") }
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = corrected), disableParallel = false)
                .intercept(requestWithResults(corrected), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                }
            assertTrue(completed is TurnOutcome.Success)
            assertEquals("9,9,9", completedOutput(upstream))
        }
    }

    @Test
    fun `escaped sequential frame includes accepted results before validating next result`() = runBlocking {
        runtime().use { runtime ->
            val bridge = bridge(runtime)
            val initial = Sink()
            bridge.interceptor(turn(), disableParallel = true)
                .intercept(BRIDGE_BASE_REQUEST, initial) { outer(threeCallsSource()) }
            var nextId = initial.ids.single()
            val accepted = mutableListOf<CodeModeResult>()
            repeat(2) {
                accepted += CodeModeResult(nextId, 0.toChar().toString().repeat(65_536))
                val next = Sink()
                val exposed = bridge.interceptor(turn().copy(toolResults = accepted.toList()), disableParallel = true)
                    .intercept(requestWithResults(accepted), next) { error("worker is still waiting") }
                assertTrue(exposed is TurnOutcome.Success)
                nextId = next.ids.single()
            }
            val stateBefore = Files.readString(tempDir.resolve("bridge.json"))
            val oversized = accepted + CodeModeResult(nextId, 0.toChar().toString().repeat(65_536))
            val rejected = bridge.interceptor(turn().copy(toolResults = oversized), disableParallel = true)
                .intercept(requestWithResults(oversized), Sink()) { error("must not post oversized results") }
            assertTrue(rejected is TurnOutcome.Failure)
            assertEquals(ErrorType.INVALID_REQUEST, (rejected as TurnOutcome.Failure).type)
            assertEquals("code-mode result frame exceeds the size limit", rejected.message)
            assertEquals(stateBefore, Files.readString(tempDir.resolve("bridge.json")))

            val corrected = accepted + CodeModeResult(nextId, "corrected")
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = corrected), disableParallel = true)
                .intercept(requestWithResults(corrected), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                }
            assertTrue(completed is TurnOutcome.Success)
            assertEquals("65536,65536,9", completedOutput(upstream))
        }
    }

    @Test
    fun `escaped frame validation excludes results from already advanced batches`() = runBlocking {
        runtime().use { runtime ->
            val bridge = bridge(runtime)
            val first = Sink()
            bridge.interceptor(turn(), disableParallel = false)
                .intercept(BRIDGE_BASE_REQUEST, first) {
                    outer(
                        """
                        const first = await tools.call('Read', {});
                        const next = await Promise.all([tools.call('Read', {}), tools.call('Read', {})]);
                        return [first.length, ...next.map(value => value.length)].join(',');
                        """.trimIndent(),
                    )
                }
            val prior = CodeModeResult(first.ids.single(), 0.toChar().toString().repeat(65_536))
            val next = Sink()
            bridge.interceptor(turn().copy(toolResults = listOf(prior)), disableParallel = false)
                .intercept(requestWithResults(listOf(prior)), next) { error("worker is still waiting") }
            assertEquals(2, next.ids.size)
            val results = listOf(prior) + next.ids.map { CodeModeResult(it, prior.output) }
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = results), disableParallel = false)
                .intercept(requestWithResults(results), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                }
            assertTrue(completed is TurnOutcome.Success)
            assertEquals("65536,65536,65536", completedOutput(upstream))
        }
    }

    private fun threeCallsSource(): String = """
        const values = await Promise.all([
          tools.call('Read', {}), tools.call('Read', {}), tools.call('Read', {})
        ]);
        return values.map(value => value.length).join(',');
    """.trimIndent()

    private fun requestWithResults(results: List<CodeModeResult>): String = buildJsonObject {
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("role", "developer")
                    put("content", "s")
                },
            )
            results.forEach { result ->
                add(
                    buildJsonObject {
                        put("type", "function_call")
                        put("call_id", result.id)
                        put("name", "Read")
                        put("arguments", "{}")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.id)
                        put("output", result.output)
                    },
                )
            }
        }
    }.toString()

    private fun completedOutput(body: String): String {
        val input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
        val output = input.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
        return output.jsonObject.getValue("output").jsonPrimitive.content
    }

    private fun runtime(timeoutMs: Long = 5_000) = JvmCodeModeRuntime(
        maxWorkers = 1,
        advanceTimeoutMs = timeoutMs,
        workerClasspath = testClasspath,
    )

    private fun bridge(runtime: JvmCodeModeRuntime, filename: String = "bridge.json") =
        CodexCodeModeBridge(CodeModeBridgeConfig(runtime, tempDir.resolve(filename)))

    private fun turn(id: String? = null, output: String = "") = CodexCodeModeBridge.Turn(
        "session",
        "conversation",
        "gpt-6-astra",
        setOf("Read"),
        id?.let { listOf(CodeModeResult(it, output)) }.orEmpty(),
    )

    private fun outer(source: String): TurnOutcome.Success {
        val raw = buildJsonObject {
            put("type", "custom_tool_call")
            put("call_id", "outer")
            put("name", "splice_exec")
            put("input", source)
        }
        return TurnOutcome.Success(
            false,
            false,
            upstreamUsage,
            customCalls = listOf(GatewayCustomCall("outer", "splice_exec", source, raw)),
        )
    }

    private fun requestWithResult(id: String, output: String): String {
        val encoded = Json.encodeToString(output)
        return """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$id","output":$encoded}]}"""
    }

    private class Sink : WireSink {
        val ids = mutableListOf<String>()
        override suspend fun openText() = WireBlockIndex(0)
        override suspend fun openThinking() = WireBlockIndex(0)
        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            ids += id
            return WireBlockIndex(ids.lastIndex)
        }
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
