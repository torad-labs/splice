// NEW: a forced code-mode host cut reaches the client as retryable while accepted results stay durable.
package splice.app.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.JvmCodeModeRuntime
import splice.codemode.host.HostLaunch
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.nio.file.Path

@Timeout(45)
internal class CodeModeWorkerLossTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `a forced host close during a resumed turn is retryable and never reruns accepted calls`() = runBlocking {
        val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))
        JvmCodeModeRuntime(launch = HostLaunch(classpath = classpath)).use { real ->
            val runtime = HeldResumeRuntime(real)
            val manager = CodexCodeModeBridge(
                CodeModeBridgeConfig(
                    { runtime },
                    CodeModeStateLocation(tempDir.resolve("state"), tempDir.resolve("old")),
                ),
            )
            val initial = Sink()
            manager.interceptor(turn(), disableParallel = false).interceptOutcome(body(), initial) { outer() }
            val id = initial.ids.single()
            val pending = async {
                manager.interceptor(turn(id), disableParallel = false)
                    .interceptOutcome(body(id), Sink()) { completed() }
            }
            runtime.entered.await()
            real.close()
            runtime.release.complete(Unit)
            val failure = pending.await() as TurnOutcome.Failure
            assertEquals(ErrorType.OVERLOADED, failure.type)
            assertFalse(failure.deterministic)
            var continued = ""
            val retry = manager.interceptor(turn(id), disableParallel = false).interceptOutcome(body(id), Sink()) {
                continued = it
                completed()
            }
            assertTrue(retry is TurnOutcome.Success)
            assertTrue("accepted" in continued)
            assertTrue("sourceRerun\\\":false" in continued, continued)
            assertEquals(1, runtime.starts)
        }
    }

    private fun turn(id: String? = null) = CodexCodeModeBridge.Turn(
        "synthetic-session",
        "synthetic-conversation",
        "gpt-6-astra",
        setOf("Read"),
        id?.let { listOf(CodeModeResult(it, "accepted")) }.orEmpty(),
    )

    private fun body(id: String? = null): String {
        val result = id?.let {
            "," + """{"type":"function_call","call_id":"$it","name":"Read","arguments":"{}"},""" +
                """{"type":"function_call_output","call_id":"$it","output":"accepted"}"""
        }.orEmpty()
        return """{"input":[{"role":"user","content":"start"}$result]}"""
    }

    private fun outer(): TurnOutcome.Success {
        val source = "return await tools.call('Read', {});"
        val raw = buildJsonObject {
            put("type", "custom_tool_call")
            put("call_id", "outer")
            put("name", "exec")
            put("input", source)
        }
        return completed().copy(customCalls = listOf(GatewayCustomCall("outer", "exec", source, raw)))
    }

    private fun completed() = TurnOutcome.Success(false, false, Usage(), messageClosed = true)

    private class HeldResumeRuntime(private val real: JvmCodeModeRuntime) : CodeModeRuntime {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var starts = 0
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            return HeldCell(real.start(source, tools, descriptions))
        }
        override fun close() = real.close()
        private inner class HeldCell(private val realCell: CodeModeCell) : CodeModeCell {
            private var advances = 0
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                if (++advances == 2) {
                    entered.complete(Unit)
                    release.await()
                }
                return realCell.advance(results)
            }
            override fun close() = realCell.close()
        }
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
