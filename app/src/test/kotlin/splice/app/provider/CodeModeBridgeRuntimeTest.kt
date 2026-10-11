// NEW: real worker deadlines and UTF-8 boundaries are verified through the Codex bridge.
package splice.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
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
import splice.codemode.CodeModeWorkerReclamation
import splice.codemode.JvmCodeModeRuntime
import splice.codemode.SCRIPT_DEADLINE_MS
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ResponseShape
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeStartException
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val BRIDGE_BASE_REQUEST = """{"input":[{"role":"developer","content":"s"}]}"""

private val COMPLETED_HEADER = Regex("^Script completed\nWall time \\d+\\.\\d seconds\nOutput:\n")

private fun Usage.asLocalStep(): Usage = copy(origin = origin.copy(localStep = true))

// A hang guard, above SCRIPT_DEADLINE_MS so the runtime's own deadline is what ends a stuck script.
@Timeout(60)
class CodeModeBridgeRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))
    private val upstreamUsage = Usage(19, 7, 5, 3)

    private fun hostLaunch(spawn: splice.codemode.WorkerSpawn) = HostLaunch(classpath = testClasspath, spawn = spawn)

    @Test
    fun `host boot deadline is retryable and preserves usage and unstarted source`() = runBlocking {
        val reclamation = CodeModeWorkerReclamation()
        JvmCodeModeRuntime(
            launch = hostLaunch(
                splice.codemode.WorkerSpawn { builder ->
                    builder.command(listOf("/bin/sh", "-c", "/bin/sleep 8; exec \"\\$0\" \"\\$@\"") + builder.command())
                    reclamation(builder)
                },
            ).copy(startTimeoutMs = 1_000),
        ).use { runtime ->
            val outcome = bridge(runtime).interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) { outer("/* private source marker */ return 1;") }
            assertTrue(outcome is TurnOutcome.Failure)
            val failure = outcome as TurnOutcome.Failure
            assertEquals(upstreamUsage, failure.salvagedUsage)
            assertEquals(ErrorType.OVERLOADED, failure.type)
            assertEquals(FailureCause.INTERNAL, failure.cause)
            assertFalse(failure.traits.deterministic)
            // Reload through the public bridge, so checkpoint and patch layouts share one recovery contract.
            runtime().use { recovered ->
                var posted = ""
                val retry = bridge(recovered).interceptor(turn(), disableParallel = false)
                    .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) {
                        posted = it
                        TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                    }
                assertTrue(retry is TurnOutcome.Success, retry.toString())
                assertEquals("1", completedOutput(posted), "the failed boot's retained source must be retryable")
            }
            assertFalse(failure.message.contains("private source marker"))
            assertFalse(failure.message.contains("cancelled"))
            reclamation.assertReclaimed(runtime)
        }
    }

    @Test
    fun `a real spawn EOF preserves source and an exact retry boots once`() = runBlocking {
        val spawns = AtomicInteger()
        val spawned = java.util.concurrent.CompletableFuture<Process>()
        JvmCodeModeRuntime(
            launch = hostLaunch(
                splice.codemode.WorkerSpawn { builder ->
                    val initial = spawns.incrementAndGet() == 1
                    if (initial) builder.command("/bin/sh", "-c", "exec /bin/sleep 60")
                    builder.start().also { if (initial) spawned.complete(it) }
                },
            ),
        ).use { runtime ->
            val manager = bridge(runtime)
            val pending = async {
                val outcome = manager.interceptor(turn(), disableParallel = false)
                    .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) { outer("return 'boot-recovered';") }
                outcome as TurnOutcome.Failure
            }
            val child = withTimeout(60_000) { spawned.await() }
            // The turn must select the first boot before its EOF, not race automatic prewarm recovery.
            yield()
            child.destroy()
            val first = pending.await()
            assertEquals(ErrorType.OVERLOADED, first.type, first.message)
            assertFalse(first.traits.deterministic)
            var posted = ""
            val retry = manager.interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) {
                    posted = it
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                }
            assertTrue(retry is TurnOutcome.Success)
            assertEquals("boot-recovered", completedOutput(posted))
            assertEquals(2, spawns.get())
            manager.interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) {
                    assertEquals("boot-recovered", completedOutput(it))
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                }
            assertEquals(2, spawns.get())
        }
    }

    @Test
    @Timeout(45)
    fun `a long resumed script completes through the bridge without replaying source`() = runBlocking {
        runtime(timeoutMs = 1).use { runtime ->
            val bridge = bridge(runtime)
            val sink = Sink()
            val first = bridge.interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, sink) {
                    outer(
                        """
                        await tools.call('Read', {});
                        const until = Date.now() + 100;
                        while (Date.now() < until) {}
                        return 'done';
                        """.trimIndent(),
                    )
                }
            assertEquals(upstreamUsage.asLocalStep(), (first as TurnOutcome.Success).usage)
            val id = sink.ids.single()
            var upstream = ""
            var posts = 0
            val completed = bridge.interceptor(turn(id, "result"), disableParallel = false)
                .interceptOutcome(requestWithResult(id, "result"), Sink()) {
                    posts++
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                }
            assertTrue(completed is TurnOutcome.Success)
            assertEquals(1, posts)
            assertEquals("done", completedOutput(upstream))
        }
    }

    @Test
    fun `parent cancellation through bridge stays cancellation and preserves the shared host`() = runBlocking {
        val spawned = java.util.concurrent.CompletableFuture<Process>()
        JvmCodeModeRuntime(
            launch = hostLaunch(
                splice.codemode.WorkerSpawn { builder -> builder.start().also { spawned.complete(it) } },
            ).copy(startTimeoutMs = 60_000),
        ).use { runtime ->
            val bridge = bridge(runtime)
            val startup = async {
                bridge.interceptor(turn(), disableParallel = false)
                    .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) { outer("while (true) {}") }
            }
            val child = withTimeout(60_000) { spawned.await() }
            yield()
            startup.cancelAndJoin()
            assertThrows(CancellationException::class.java) { runBlocking { startup.await() } }
            assertTrue(child.isAlive, "a cancelled script must not reap the shared host")
            val replacement = withTimeout(60_000) { runtime.start("return 'alive';", emptySet()) }
            assertEquals("alive", (replacement.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `bridge rejects oversized UTF8 source before allocating a worker`() = runBlocking {
        runtime().use { runtime ->
            val occupied = runtime.start("await tools.call('Read', {});", setOf("Read"))
            val source = "//" + "é".repeat(32_768)
            val failure = bridge(runtime).interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) { outer(source) } as TurnOutcome.Failure
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
                .interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) {
                    if (++posts == 1) {
                        outer(source)
                    } else {
                        TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                    }
                }
            assertTrue(outcome is TurnOutcome.Success)
            assertEquals(2, posts)
        }
    }

    // V4-107: a head restart is onHeadStop then start on the same provider, and the real runtime's
    // close() is terminal, so the bridge must run the next script on a runtime it opens fresh.
    @Test
    fun `code mode survives a head restart on the real runtime`() = runBlocking<Unit> {
        val opened = mutableListOf<JvmCodeModeRuntime>()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                { runtime().also(opened::add) },
                CodeModeStateLocation(tempDir.resolve("restart"), tempDir.resolve("restart.json")),
            ),
        )
        try {
            assertTrue(script(bridge, "return 'before';", "outer-1") is TurnOutcome.Success)
            bridge.onHeadStop()
            val after = script(bridge, "return 'after';", "outer-2")
            assertTrue(after is TurnOutcome.Success, "the first script after a restart must run: $after")
            assertEquals(2, opened.size, "the restarted head opened its own runtime")
            val closed = assertThrows(CodeModeStartException::class.java) {
                runBlocking { opened.first().start("return 1;", emptySet()) }
            }
            assertTrue(closed.cause is IllegalStateException)
        } finally {
            bridge.onHeadStop()
        }
    }

    private suspend fun script(bridge: CodexCodeModeBridge, source: String, callId: String): TurnOutcome {
        var posts = 0
        return bridge.interceptor(turn(), disableParallel = false).interceptOutcome(BRIDGE_BASE_REQUEST, Sink()) {
            if (++posts == 1) {
                outer(source, callId)
            } else {
                TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
            }
        }
    }

    @Test
    fun `bridge result UTF8 boundary matches the real worker`() = runBlocking {
        // 65_536 bytes fits untouched; 65_538 is admitted truncated (never rejected: Claude Code
        // cannot send a corrected result) and the turn still completes through the real worker.
        for (bytes in listOf(65_536, 65_538)) {
            runtime().use { runtime ->
                val bridge = bridge(runtime, "result-$bytes.json")
                val sink = Sink()
                bridge.interceptor(turn(), disableParallel = false)
                    .interceptOutcome(BRIDGE_BASE_REQUEST, sink) { outer("return await tools.call('Read', {});") }
                val id = sink.ids.single()
                val output = "é".repeat(bytes / 2)
                var posts = 0
                var upstream = ""
                val outcome = bridge.interceptor(turn(id, output), disableParallel = false)
                    .interceptOutcome(requestWithResult(id, output), Sink()) {
                        posts++
                        upstream = it
                        TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
                    }
                assertTrue(outcome is TurnOutcome.Success, "$bytes: $outcome")
                assertEquals(1, posts)
                assertEquals(bytes > 65_536, upstream.contains("[truncated "), "$bytes: marker")
            }
        }
    }

    @Test
    fun `oversized escaped parallel frame leaves results unconsumed and corrected retry works`() = runBlocking {
        runtime().use { runtime ->
            val bridge = bridge(runtime)
            val sink = Sink()
            bridge.interceptor(turn(), disableParallel = false)
                .interceptOutcome(BRIDGE_BASE_REQUEST, sink) { outer(threeCallsSource()) }
            assertEquals(3, sink.ids.size)
            val oversized = sink.ids.map { CodeModeResult(it, 0.toChar().toString().repeat(65_536)) }
            val stateBefore = saved()
            val rejected = bridge.interceptor(turn().copy(toolResults = oversized), disableParallel = false)
                .interceptOutcome(requestWithResults(oversized), Sink()) { error("must not post oversized results") }
            assertTrue(rejected is TurnOutcome.Failure)
            assertEquals(ErrorType.INVALID_REQUEST, (rejected as TurnOutcome.Failure).type)
            assertEquals("code-mode result frame exceeds the size limit", rejected.message)
            assertEquals(stateBefore, saved())

            val corrected = sink.ids.map { CodeModeResult(it, "corrected") }
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = corrected), disableParallel = false)
                .interceptOutcome(requestWithResults(corrected), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
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
                .interceptOutcome(BRIDGE_BASE_REQUEST, initial) { outer(threeCallsSource()) }
            var nextId = initial.ids.single()
            val accepted = mutableListOf<CodeModeResult>()
            repeat(2) {
                accepted += CodeModeResult(nextId, 0.toChar().toString().repeat(65_536))
                val next = Sink()
                val exposed = bridge.interceptor(turn().copy(toolResults = accepted.toList()), disableParallel = true)
                    .interceptOutcome(requestWithResults(accepted), next) { error("worker is still waiting") }
                assertTrue(exposed is TurnOutcome.Success)
                nextId = next.ids.single()
            }
            val stateBefore = saved()
            val oversized = accepted + CodeModeResult(nextId, 0.toChar().toString().repeat(65_536))
            val rejected = bridge.interceptor(turn().copy(toolResults = oversized), disableParallel = true)
                .interceptOutcome(requestWithResults(oversized), Sink()) { error("must not post oversized results") }
            assertTrue(rejected is TurnOutcome.Failure)
            assertEquals(ErrorType.INVALID_REQUEST, (rejected as TurnOutcome.Failure).type)
            assertEquals("code-mode result frame exceeds the size limit", rejected.message)
            assertEquals(stateBefore, saved())

            val corrected = accepted + CodeModeResult(nextId, "corrected")
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = corrected), disableParallel = true)
                .interceptOutcome(requestWithResults(corrected), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
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
                .interceptOutcome(BRIDGE_BASE_REQUEST, first) {
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
                .interceptOutcome(requestWithResults(listOf(prior)), next) { error("worker is still waiting") }
            assertEquals(2, next.ids.size)
            val results = listOf(prior) + next.ids.map { CodeModeResult(it, prior.output) }
            var upstream = ""
            val completed = bridge.interceptor(turn().copy(toolResults = results), disableParallel = false)
                .interceptOutcome(requestWithResults(results), Sink()) {
                    upstream = it
                    TurnOutcome.Success(false, false, Usage(), shape = ResponseShape(messageClosed = true))
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

    /** V4-388: the script's own output, read under codex's "Script completed" exec header. */
    private fun completedOutput(body: String): String {
        val input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
        val output = input.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
        val framed = output.jsonObject.getValue("output").jsonPrimitive.content
        val header = checkNotNull(COMPLETED_HEADER.find(framed)) { "not a completed exec output: ${framed.take(80)}" }
        return framed.substring(header.range.last + 1)
    }

    private fun runtime(timeoutMs: Long = SCRIPT_DEADLINE_MS) = JvmCodeModeRuntime(
        limits = PoolLimits(maxWorkers = 1),
        launch = HostLaunch(classpath = testClasspath),
        advanceTimeoutMs = timeoutMs,
    )

    private fun bridge(runtime: JvmCodeModeRuntime, filename: String = "bridge.json") =
        CodexCodeModeBridge(
            CodeModeBridgeConfig({ runtime }, CodeModeStateLocation(savedDir(filename), tempDir.resolve(filename))),
        )

    /** Where [bridge] keeps the records it was given [filename] for: one file per conversation (V4-340). */
    private fun savedDir(filename: String = "bridge.json") = tempDir.resolve("$filename.d")

    /** Everything [bridge] has saved: each conversation's file, in name order. */
    private fun saved(): String =
        Files.list(savedDir()).use { files -> files.sorted().map(Files::readString).toList() }.joinToString("\n")

    private fun turn(id: String? = null, output: String = "") = CodexCodeModeBridge.Turn(
        "session",
        "conversation",
        "gpt-6-astra",
        setOf("Read"),
        id?.let { listOf(CodeModeResult(it, output)) }.orEmpty(),
    )

    private fun outer(source: String, callId: String = "outer"): TurnOutcome.Success {
        val raw = buildJsonObject {
            put("type", "custom_tool_call")
            put("call_id", callId)
            put("name", "splice_exec")
            put("input", source)
        }
        return TurnOutcome.Success(
            false,
            false,
            upstreamUsage,
            handoffs = RoundHandoffs(customCalls = listOf(GatewayCustomCall(callId, "splice_exec", source, raw))),
        )
    }

    private fun requestWithResult(id: String, output: String): String {
        val encoded = Json.encodeToString(output)
        return """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$id",""" +
            """"name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$id","output":$encoded}]}"""
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
