import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.ResponsesReanchorController
import splice.gateway.round.RoundInterception
import splice.gateway.round.RoundStrategy
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.SseEmitterFactory
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import java.io.IOException
import java.nio.file.Path

class CodexCodeModeReanchorTest {
    @TempDir
    lateinit var tempDir: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `local runtime failure after visible output cannot reanchor or regenerate source`(prose: Boolean) = runTest {
        val runtime = Runtime(fail = true)
        var posts = 0
        var finished: TurnOutcome? = null
        val strategy = strategy(runtime, { finished = it }) {
            posts++
            outer("call-$posts").copy(
                bodyText = if (prose) "visible prose" else "",
                emittedText = prose,
                thinkingText = "visible reasoning",
                emittedThinking = true,
            )
        }
        strategy.run(body, null, ResponsesReanchorController({ null }, maxContinuations = 1))
        val failure = finished as TurnOutcome.Failure
        assertEquals(1, posts, "local failure must not produce an upstream marker retry")
        assertEquals(1, runtime.starts)
        assertNull(failure.partial)
        assertEquals(Usage(19, 7, 5, 3), failure.salvagedUsage)
    }

    @Test
    fun `genuine upstream interruption after completed script retains ordinary recovery`() = runTest {
        val runtime = Runtime(fail = false)
        var posts = 0
        var finished: TurnOutcome? = null
        val strategy = strategy(runtime, { finished = it }) {
            when (posts++) {
                0 -> outer("first")
                1 -> TurnOutcome.Failure(
                    ErrorType.API_ERROR,
                    "upstream interrupted",
                    providerReported = true,
                    partial = TurnOutcome.PartialRound(bodyText = "visible", emittedText = true),
                )
                else -> TurnOutcome.Success(false, false, Usage(23, 2), messageClosed = true)
            }
        }
        strategy.run(body, null, ResponsesReanchorController({ null }, maxContinuations = 1))
        assertTrue(finished is TurnOutcome.Success, finished.toString())
        assertEquals(3, posts)
        assertEquals(1, runtime.starts)
        assertEquals(9, (finished as TurnOutcome.Success).usage.outputTokens)
    }

    private fun strategy(
        runtime: Runtime,
        finish: suspend (TurnOutcome) -> Unit,
        post: suspend (String) -> TurnOutcome,
    ): RoundStrategy {
        val bridge = CodexCodeModeBridge(CodeModeBridgeConfig(runtime, tempDir.resolve("state.json")))
        val turn = CodexCodeModeBridge.Turn("session", "conversation", "gpt-6-astra", setOf("Read"), emptyList())
        val emitter = SseEmitterFactory().create({}, "model", { buildJsonObject { } })
        return RoundStrategy(
            key = "test",
            log = {},
            emitter = emitter,
            signals = RunnerSignals(watchdogFired = { false }, clientGone = { false }),
            postRoundToSink = { request, _ -> post(request) },
            postRound = { post(it) },
            finish = { finish(it) },
            interception = RoundInterception(interceptor = bridge.interceptor(turn, disableParallel = false)),
        )
    }

    private fun outer(id: String): TurnOutcome.Success {
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"$id","name":"splice_exec","input":"source"}""",
        ).jsonObject
        return TurnOutcome.Success(
            false,
            false,
            Usage(19, 7, 5, 3),
            bodyText = "visible prose",
            emittedText = true,
            customCalls = listOf(GatewayCustomCall(id, "splice_exec", "source", raw)),
        )
    }

    private val body = Json.parseToJsonElement(
        """{"model":"gpt-6-astra","store":false,"stream":true,"input":[{"role":"developer","content":"s"}]}""",
    ).jsonObject

    private class Runtime(private val fail: Boolean) : CodeModeRuntime {
        var starts = 0
        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            starts++
            if (fail) throw IOException("synthetic startup failure")
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                    CodeModeStep.Completed("done")
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }
}
