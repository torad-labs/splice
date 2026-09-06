import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.spi.BuiltTurn
import splice.spi.CodeModeCall
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import splice.spi.WireSink
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

internal const val BASE_REQUEST: String = """{"input":[{"role":"developer","content":"s"}]}"""

abstract class CodeModeBridgeTestSupport {
    @TempDir
    protected lateinit var tempDir: Path

    protected fun bridge(
        runtime: CodeModeRuntime,
        maxRecords: Int = 8,
        maxRounds: Int = 32,
        ttl: Duration = 24.hours,
        clock: Clock = Clock.systemUTC(),
    ) = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            runtime,
            tempDir.resolve("bridge.json"),
            maxRecords = maxRecords,
            ttl = ttl,
            maxRounds = maxRounds,
            clock = clock,
        ),
    )

    protected fun built(model: String, lite: Boolean): BuiltTurn {
        val prefix = if (lite) {
            """{"input":[{"type":"additional_tools","role":"developer","tools":[{"type":"function","name":"Read"}]},{"role":"developer","content":""}]}"""
        } else {
            """{"input":[],"tools":[{"type":"function","name":"Read"}]}"""
        }
        return BuiltTurn(
            Json.parseToJsonElement(prefix).jsonObject,
            TurnMeta(false, ReasoningDisplay.OFF, true, model, model, 100, "high", null, null),
        )
    }

    protected fun toolBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":"hi"}]}""",
    )

    protected fun toollessBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""",
    )

    protected fun namedChoiceBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"tool_choice":{"type":"tool","name":"Read"},"messages":[{"role":"user","content":"hi"}]}""",
    )

    protected fun nonTextResultBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_splice_test","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]}]}""",
    )

    protected fun historicalImageResultBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"ordinary-history-id","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]},{"role":"user","content":"continue"}]}""",
    )

    protected fun turn(
        resultId: String? = null,
        result: String = "",
        sessionId: String = "session-a",
        results: List<CodeModeResult>? = null,
    ) = CodexCodeModeBridge.Turn(
        sessionId = sessionId,
        conversationKey = "splice-first-message",
        model = "gpt-6-astra",
        tools = setOf("Read", "Edit"),
        toolResults = results ?: resultId?.let { listOf(CodeModeResult(it, result)) }.orEmpty(),
    )

    protected fun outer(callId: String = "outer-call", source: String = "source") = GatewayCustomCall(
        callId = callId,
        name = "splice_exec",
        input = source,
        raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"$callId","name":"splice_exec","input":"$source"}""",
        ).jsonObject,
    )

    protected fun outerOutcome(callId: String = "outer-call") =
        TurnOutcome.Success(false, false, Usage(), customCalls = listOf(outer(callId)))

    protected fun completedOutcome() = TurnOutcome.Success(false, false, Usage(), messageClosed = true)

    protected fun requestWithCall(id: String) =
        """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}]}"""

    protected fun requestWithResult(id: String, output: String) =
        """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$id","output":"$output"}]}"""

    protected fun requestWithTwoResults(first: String, second: String) =
        """{"input":[{"role":"developer","content":"s"},{"type":"function_call","call_id":"$first","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$first","output":"A"},{"type":"function_call","call_id":"$second","name":"Edit","arguments":"{}"},{"type":"function_call_output","call_id":"$second","output":"B"}]}"""

    protected fun requestWithSiblingBeforeResult(id: String, sibling: String) =
        """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"$sibling"},{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},{"type":"function_call_output","call_id":"$id","output":"A"}]}"""

    protected fun call(id: String, name: String, arg: Pair<String, String>? = null) = CodeModeCall(
        id,
        name,
        buildJsonObject { arg?.let { put(it.first, it.second) } },
    )

    protected class BlockingRuntime : CodeModeRuntime {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<CodeModeCell>()
        var cell: ScriptedCell? = null

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            started.complete(Unit)
            return release.await()
        }

        override fun close() = Unit
    }

    protected class QueuedRuntime(private val queued: ArrayDeque<ArrayDeque<CodeModeStep>>) : CodeModeRuntime {
        val sources = mutableListOf<String>()
        val cells = mutableListOf<ScriptedCell>()
        val starts: Int get() = sources.size

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            sources += source
            return ScriptedCell(queued.removeFirst()).also(cells::add)
        }

        override fun close() = Unit
    }

    protected class ScriptedRuntime(steps: ArrayDeque<CodeModeStep>) : CodeModeRuntime {
        val cell = ScriptedCell(steps)
        var starts = 0

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            starts++
            return cell
        }

        override fun close() = Unit
    }

    protected class ScriptedCell(private val steps: ArrayDeque<CodeModeStep>) : CodeModeCell {
        var advances = 0
        var closed = false
        val results = mutableListOf<List<CodeModeResult>>()

        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
            advances++
            this.results += results
            return steps.removeFirst()
        }

        override fun close() {
            closed = true
        }
    }

    protected class MutableClock(var now: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
    }

    protected data class SeenTool(
        val id: String,
        val name: String,
        val args: StringBuilder = StringBuilder(),
    )

    protected class RecordingSink : WireSink {
        val tools = mutableListOf<SeenTool>()
        override suspend fun openText() = WireBlockIndex(0)
        override suspend fun openThinking() = WireBlockIndex(0)

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            tools += SeenTool(id, name)
            return WireBlockIndex(tools.lastIndex)
        }

        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
            tools[index.value].args.append(partialJson)
        }

        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }

    private fun parseBody(value: String) = AnthropicParse.parseAnthropicBody(value)
}
