package splice.app.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.JvmCodeModeRuntime
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeResult
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class CodeModeStatementForceCostTest {
    @TempDir
    lateinit var tempDir: Path

    private val state get() = tempDir.resolve("state")
    private val base = """{"input":[{"role":"developer","content":"synthetic force measurement"}]}"""

    @Test
    fun `measure durable writes while the real worker executes independently streamed statements`(
        reporter: TestReporter,
    ) = runBlocking<Unit> {
        val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = classpath).use { runtime ->
            val manager = CodexCodeModeBridge(
                CodeModeBridgeConfig({ runtime }, CodeModeStateLocation(state, tempDir.resolve("legacy.json"))),
            )
            val post = StatementStreamPost()
            val results = mutableListOf<CodeModeResult>()
            try {
                repeat(post.gates.size) { index ->
                    post.gates[index].complete(Unit)
                    val sink = Sink()
                    val turn = turn(results.toList())
                    reporter.publishEntry("statementIndex", index.toString())
                    val outcome = withTimeout(10_000) {
                        manager.interceptor(turn, disableParallel = false)
                            .intercept(if (results.isEmpty()) base else request(results), sink, post)
                    }
                    assertTrue(outcome is TurnOutcome.Success, outcome.toString())
                    assertEquals(1, sink.ids.size, "one independently executed tool statement $index")
                    results += CodeModeResult(sink.ids.single(), "synthetic result $index")
                }
                var posted = ""
                val completed = manager.interceptor(turn(results), disableParallel = false)
                    .intercept(request(results), Sink()) {
                        posted = it
                        TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                    }
                assertTrue(completed is TurnOutcome.Success, completed.toString())
                val output = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
                    .single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
                    .jsonObject.getValue("output").jsonPrimitive.content
                assertTrue(output.endsWith("\nmeasured"), output)
                report(reporter, results.size, post.deltas)
            } finally {
                manager.onHeadStop()
            }
        }
    }

    private fun report(reporter: TestReporter, statements: Int, deltas: Int) {
        val journal = Files.list(state).use { files ->
            files.filter { it.fileName.toString().endsWith(".jsonl") }.toList().single()
        }
        val transactions = Files.readAllLines(journal).count(String::isNotBlank)
        val receipt = """{"executedToolStatements":$statements,"producerDeltas":$deltas,"forcedWriteTransactions":$transactions}"""
        reporter.publishEntry("statementForceCost", receipt)
        val output = Path.of("build/reports/statement-force-cost.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, receipt + "\n")
    }

    private fun turn(results: List<CodeModeResult>) =
        CodexCodeModeBridge.Turn("force-session", "force-conversation", "synthetic-model", setOf("Read"), results)

    private fun request(results: List<CodeModeResult>): String = buildJsonObject {
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("role", "developer")
                    put("content", "synthetic force measurement")
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

    private inner class StatementStreamPost : RedirectableRoundPost {
        val gates = List(20) { CompletableDeferred<Unit>() }
        var deltas = 0
        private val statement = "text(await tools.Read({}));\n"
        private val source = statement.repeat(gates.size) + "text('measured');"

        override suspend fun invoke(bodyJson: String): TurnOutcome =
            error("the streaming driver supplies its wire sink")

        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
            val call = call("")
            sink.customToolSource(CustomToolSource.Started(call))
            gates.forEachIndexed { index, gate ->
                gate.await()
                // Supply the next token, as the native boundary fixtures do, without its next statement.
                val prefix = if (index == 0) statement else statement.removePrefix("text(")
                val nextToken = "text("
                (prefix + nextToken).chunked(3).forEach { fragment ->
                    sink.customToolSource(CustomToolSource.Delta(call.callId, fragment))
                    deltas++
                }
            }
            val terminal = call(source)
            sink.customToolSource(CustomToolSource.Completed(terminal))
            return TurnOutcome.Success(false, false, Usage(), customCalls = listOf(terminal))
        }

        private fun call(source: String) = GatewayCustomCall(
            "force-outer",
            "splice_exec",
            source,
            buildJsonObject {
                put("type", "custom_tool_call")
                put("call_id", "force-outer")
                put("name", "splice_exec")
                put("input", source)
            },
        )
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
