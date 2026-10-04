// NEW: synthetic client boundaries count real file and directory forces, without a native worker.
package splice.provider.codex

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.stream.CodeModeRoundInterceptor
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

private const val STREAMED_STATEMENT = "text(await tools.Read({}));\n"

@Timeout(30)
class CodeModeStatementForceCostTest {
    @TempDir
    lateinit var dir: Path

    private val content = "synthetic history ".repeat(4096)
    private val location get() = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))

    private data class ForceSpan(val start: Long, val end: Long, val directory: Boolean)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `one streamed statement batch forces only before client-visible state`(
        suspendBetweenChunks: Boolean,
        reporter: TestReporter,
    ) =
        runBlocking<Unit> {
            val forces = mutableListOf<ForceSpan>()
            val config = CodeModeBridgeConfig({ StatementRuntime() }, location)
            val manager = MeasuredBridge(
                config,
                CodeModeStateWrite { path, text ->
                    CodeModeStateJournal.write(path, text) { target, channel ->
                        val start = System.nanoTime()
                        channel.force(true)
                        forces += ForceSpan(start, System.nanoTime(), Files.isDirectory(target))
                    }
                },
            )
            val post = StatementPost(suspendBetweenChunks)
            val results = mutableListOf<CodeModeResult>()
            val timings = mutableListOf<Long>()
            val allocations = mutableListOf<Long>()
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            try {
                repeat(post.gates.size) { index ->
                    post.gates[index].complete(Unit)
                    val sink = ClientSink(post, index, forces, timings)
                    val body = request(results)
                    val before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
                    val outcome = manager.interceptor(turn(results)).intercept(body, sink, post)
                    allocations += bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before
                    assertTrue(outcome is TurnOutcome.Success, outcome.toString())
                    assertEquals(1, sink.ids.size)
                    results += CodeModeResult(sink.ids.single(), "synthetic result $index")
                }
                val completed = manager.interceptor(turn(results)).intercept(request(results), QuietSink(), post)
                assertTrue(completed is TurnOutcome.Success, completed.toString())
                report(reporter, forces, post, timings, allocations)
                assertEquals(post.gates.size, timings.size)
                assertTrue(
                    forces.size <= post.gates.size + 5,
                    "one issuance force per statement plus restart boundaries",
                )
            } finally {
                manager.stop()
            }
        }

    @Test
    fun `an abrupt process kill after dispatch but before the first effect never reruns source`() = runBlocking {
        val effect = dir.resolve("synthetic-effect.txt")
        val errors = dir.resolve("synthetic-child-errors.txt")
        val child = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-Xmx64m",
            "-cp",
            checkNotNull(System.getProperty("codex.testClasspath")),
            DispatchCrashProbe::class.java.name,
            dir.toString(),
            effect.toString(),
        ).redirectError(errors.toFile()).start()
        val dispatched: String
        try {
            dispatched = checkNotNull(child.inputStream.bufferedReader().readLine()) { Files.readString(errors) }
            assertTrue(dispatched.startsWith("DISPATCHED:"))
        } finally {
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "the owned synthetic child was not reaped")
        }
        assertTrue(Files.notExists(effect), "the first dispatched statement has not performed its effect")
        var starts = 0
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                starts++
                Files.writeString(effect, "rerun effect", CREATE, APPEND)
                return object : CodeModeCell {
                    override suspend fun advance(results: List<CodeModeResult>) = CodeModeStep.Completed("done")
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, location))
        try {
            manager.interceptor(crashTurn(), crashOuter(), disableParallel = false)
                .intercept(CRASH_BODY, QuietSink()) { crashOutcome() }
            assertEquals(0, starts, "dispatch without a client callback must still be durably no-rerun")
            assertTrue(Files.notExists(effect))
            assertEquals("DISPATCHED:2", dispatched, "both file and directory force must precede dispatch")
        } finally {
            manager.onHeadStop()
        }
    }

    private fun report(
        reporter: TestReporter,
        forces: List<ForceSpan>,
        post: StatementPost,
        timings: List<Long>,
        allocations: List<Long>,
    ) {
        val ordered = timings.sorted()
        val fileForces = forces.count { !it.directory }
        val receipt = buildJsonObject {
            put("statements", post.gates.size)
            put("producerDeltas", post.deltas)
            put("clientVisibleIssuances", timings.size)
            put("actualForceCalls", forces.size)
            put("fileForces", fileForces)
            put("directoryForces", forces.size - fileForces)
            put("forcesPerStatement", forces.size.toDouble() / post.gates.size)
            put("forcesPerClientVisibleIssuance", forces.size.toDouble() / timings.size)
            put("upstreamEventToClientWriteP50Ms", ordered[ordered.size / 2] / 1_000_000.0)
            put("upstreamEventToClientWriteMaxMs", ordered.last() / 1_000_000.0)
            put("allocatedBytesPerTurn", allocations.drop(5).average())
        }.toString()
        reporter.publishEntry("statementForceCost", receipt)
        val output = Path.of("build/reports/statement-force-cost.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, receipt + "\n")
    }

    private fun turn(results: List<CodeModeResult>) = CodexCodeModeBridge.Turn(
        "synthetic-session",
        "synthetic-conversation",
        "synthetic-model",
        setOf("Read"),
        results,
    )

    private fun request(results: List<CodeModeResult>): String = buildJsonObject {
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("role", "developer")
                    put("content", content)
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

    /** Uses the production interceptor, driver and machine with the existing registry writer seam. */
    private class MeasuredBridge(config: CodeModeBridgeConfig, writer: CodeModeStateWrite) {
        private val json = Json { encodeDefaults = true }
        private val wire = CodexCodeModeWire(json, config.log)
        private val run = CodeModeRuntimeRun(config.runtimes)
        private val registry = CodexCodeModeRegistry(config, json, 1.hours, writer)
        private val validation = CodexCodeModeValidation(config)
        private val machine = CodexCodeModeMachine(config, registry, validation)
        private val driver = CodexCodeModeDriver(config, run, registry, wire, validation, machine)
        private val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
        private val controller = CodexCodeModeTurn(registry, wire, driver, resume, machine, validation, config.log)

        fun interceptor(turn: CodexCodeModeBridge.Turn) =
            CodeModeRoundInterceptor(turn, null, false, wire, controller, driver.streams)

        fun stop() {
            driver.streams.stop()
            registry.onHeadStop()
            run.end()
        }
    }

    private class StatementRuntime : CodeModeRuntime {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("synthetic session uses streamed source")

        override suspend fun startStreaming(
            source: CodeModeSource,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = object : CodeModeCell {
            private var sequence = 0
            private val pending = StringBuilder()
            private var completed = false

            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                if (sequence == 0) {
                    assertTrue(results.isEmpty())
                } else {
                    assertEquals("runtime-${sequence - 1}", results.single().id)
                }
                while (true) {
                    val boundary = pending.indexOf("\n")
                    if (boundary >= 0) {
                        // A source read is an arbitrary prefix, not a complete producer statement.
                        assertEquals(STREAMED_STATEMENT, pending.substring(0, boundary + 1))
                        pending.delete(0, boundary + 1)
                        return CodeModeStep.Calls(
                            listOf(CodeModeCall("runtime-${sequence++}", "Read", buildJsonObject {})),
                        )
                    }
                    if (completed) {
                        assertEquals("text('measured');", pending.toString())
                        return CodeModeStep.Completed("synthetic completion")
                    }
                    when (val part = source.read()) {
                        is CodeModeSourcePart.Delta -> pending.append(part.text)
                        is CodeModeSourcePart.Complete -> {
                            pending.append(part.text)
                            completed = true
                        }
                        is CodeModeSourcePart.Failed -> error(part.error)
                    }
                }
            }

            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private class StatementPost(private val suspendBetweenChunks: Boolean) : RedirectableRoundPost {
        val gates = List(20) { CompletableDeferred<Unit>() }
        val eventTimes = LongArray(gates.size)
        var deltas = 0
        private var posts = 0
        private val source = STREAMED_STATEMENT.repeat(gates.size) + "text('measured');"

        override suspend fun invoke(bodyJson: String): TurnOutcome = error("streaming sink is required")

        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
            if (posts++ > 0) return TurnOutcome.Success(false, false, Usage(), messageClosed = true)
            val call = GatewayCustomCall(
                "synthetic-outer",
                CODE_MODE_TOOL_NAME,
                "",
                buildJsonObject {
                    put("type", "custom_tool_call")
                    put("call_id", "synthetic-outer")
                    put("name", CODE_MODE_TOOL_NAME)
                    put("input", "")
                },
            )
            sink.customToolSource(CustomToolSource.Started(call))
            gates.forEachIndexed { index, gate ->
                gate.await()
                eventTimes[index] = System.nanoTime()
                STREAMED_STATEMENT.chunked(3).forEach { fragment ->
                    deltas++
                    sink.customToolSource(CustomToolSource.Delta(call.callId, fragment))
                    if (suspendBetweenChunks) yield()
                }
            }
            val completed = call.copy(input = source, raw = JsonObject(call.raw + ("input" to JsonPrimitive(source))))
            sink.customToolSource(CustomToolSource.Completed(completed))
            return TurnOutcome.Success(false, false, Usage(outputTokens = 1), customCalls = listOf(completed))
        }
    }

    private class ClientSink(
        private val post: StatementPost,
        private val statement: Int,
        private val forces: List<ForceSpan>,
        private val timings: MutableList<Long>,
    ) : WireSink by QuietSink() {
        val ids = mutableListOf<String>()

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            val written = System.nanoTime()
            val event = post.eventTimes[statement]
            assertTrue(forces.any { !it.directory && it.start >= event && it.end <= written })
            timings += written - event
            ids += id
            return WireBlockIndex(1)
        }
    }

    /** The parent kills this daemon-shaped process while its first runtime dispatch waits before an effect. */
    object DispatchCrashProbe {
        @JvmStatic
        fun main(args: Array<String>) = runBlocking<Unit> {
            val root = Path.of(args[0])
            val effect = Path.of(args[1])
            var forces = 0
            val runtime = object : CodeModeRuntime {
                override suspend fun start(
                    source: String,
                    tools: Set<String>,
                    descriptions: Map<String, String>,
                ): CodeModeCell {
                    println("DISPATCHED:$forces")
                    System.out.flush()
                    System.`in`.read()
                    Files.writeString(effect, "first effect", CREATE, APPEND)
                    return object : CodeModeCell {
                        override suspend fun advance(results: List<CodeModeResult>) = CodeModeStep.Completed("done")
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
            val config = CodeModeBridgeConfig(
                { runtime },
                CodeModeStateLocation(root.resolve("state"), root.resolve("legacy.json")),
            )
            val manager = MeasuredBridge(
                config,
                CodeModeStateWrite { path, text ->
                    CodeModeStateJournal.write(path, text) { _, channel ->
                        channel.force(true)
                        forces++
                    }
                },
            )
            manager.interceptor(crashTurn()).intercept(CRASH_BODY, QuietSink()) { crashOutcome() }
        }
    }

    private companion object {
        fun crashTurn() = CodexCodeModeBridge.Turn("crash-session", "crash-conversation", "synthetic", setOf("Read"))
        const val CRASH_BODY = """{"input":[{"role":"developer","content":"synthetic crash point"}]}"""
        fun crashOuter() = GatewayCustomCall(
            "crash-outer",
            CODE_MODE_TOOL_NAME,
            "text(await tools.Read({}));",
            buildJsonObject {
                put("type", "custom_tool_call")
                put("call_id", "crash-outer")
                put("name", CODE_MODE_TOOL_NAME)
                put("input", "text(await tools.Read({}));")
            },
        )
        fun crashOutcome() = TurnOutcome.Success(false, false, Usage(), customCalls = listOf(crashOuter()))
    }

    private class QuietSink : WireSink {
        override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) = Unit
        override suspend fun openText(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(1)
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
