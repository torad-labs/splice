package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** V4-441: the models the backend's catalog marks `tool_mode = "code_mode_only"`, which is what runs code mode
 *  in production; the suites below that arm a turn on one of these hand the builder the same port. */
internal val backendCodeModeOnly: CodeModeOnlyModels = CodeModeOnlyModels {
    listOf("gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
}

internal const val BASE_REQUEST: String = """{"input":[{"role":"developer","content":"s"}]}"""

private val TERMINATED_HEADER = Regex("^Script terminated\nWall time \\d+\\.\\d seconds\nOutput:\n")

/** V4-388: an interruption's evidence JSON under codex's "Script terminated" exec header. */
internal fun terminatedEvidence(output: String): JsonObject {
    val header = checkNotNull(TERMINATED_HEADER.find(output)) { "not a terminated exec output: ${output.take(80)}" }
    return Json.parseToJsonElement(output.substring(header.range.last + 1)).jsonObject
}

abstract class CodeModeBridgeTestSupport {
    @TempDir
    protected lateinit var tempDir: Path

    /** Every line the bridge logged through its head-scoped sink, across every bridge built here. */
    protected val logLines = mutableListOf<String>()
    protected val deadSessions = mutableSetOf<String>()

    /** The code-mode state [bridge] keeps: one file per conversation (V4-340). */
    protected val stateFiles by lazy { CodeModeStateFiles(tempDir.resolve("code-mode")) }

    /** [stateFiles]'s directory, and the single file an older daemon kept it in. */
    protected fun stateLocation() = CodeModeStateLocation(stateFiles.dir, tempDir.resolve("bridge.json"))

    /** [maxRecords] is the head's record count (V4-337's [CodeModeRetention.records]).
     *  [clock] stays fixed unless a time-sensitive test explicitly supplies [MutableClock]. */
    protected fun bridge(
        runtime: CodeModeRuntime,
        maxRecords: Int = 8,
        maxRounds: Int = 32,
        ttl: Duration = 24.hours,
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneId.of("UTC")),
        retention: CodeModeRetention = CodeModeRetention(records = maxRecords),
    ) = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            { runtime },
            stateLocation(),
            retention = retention,
            ttl = ttl,
            maxRounds = maxRounds,
            clock = clock,
            cellClock = ElapsedClock(clock::millis),
            log = LogSink { logLines += it },
            sessionAlive = CodeModeSessionAlive { id -> if (id in deadSessions) false else null },
        ),
    )

    /** Exercise this conversation's history lookup without advancing or resolving its parked script. */
    protected fun sweepOwnHistory(manager: CodexCodeModeBridge) {
        val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val registry = field.get(manager) as CodexCodeModeRegistry
        val key = stateFiles.records().first().getValue("key").jsonPrimitive.content
        registry.recordsFor(key)
    }

    /** Exercise the capacity backstop rather than killing a request's own retained cell on lookup. */
    protected fun reapIdleCell(manager: CodexCodeModeBridge) {
        val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
        (field.get(manager) as CodexCodeModeRegistry).evictIdleCell()
    }

    /** The dialect's tool_result image renderer with codex's own quirks — the one production uses. */
    protected fun media() = ResponsesToolResultMedia(CodexQuirks().defaultQuirks())

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

    /** V4-179: an image the renderer cannot map (empty payload) — omitted, with DR-164's reason. */
    protected fun unreadableResultBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_splice_test","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}]}]}]}""",
    )

    /** V4-114 / V4-178: a bridge tool result whose content is text AND then an image.
     *  CodexCodeModeTurnBuilder.toolResults must never quietly keep the text half —
     *  `filterIsInstance<TextBlock>()` is the silent-drop shape; since V4-178 the image is kept as
     *  an announced marker in its place rather than refused (the refusal wedged a live session). */
    protected fun mixedResultBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_splice_test","content":[{"type":"text","text":"ok"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]}]}""",
    )

    protected fun historicalImageResultBody() = parseBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"ordinary-history-id","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]},{"role":"user","content":"continue"}]}""",
    )

    protected fun turn(
        resultId: String? = null,
        result: String = "",
        sessionId: String = "session-a",
        results: List<CodeModeResult>? = null,
        model: String = "gpt-6-astra",
    ) = CodexCodeModeBridge.Turn(
        sessionId = sessionId,
        conversationKey = "splice-first-message",
        model = model,
        tools = setOf("Read", "Edit"),
        toolResults = results ?: resultId?.let { listOf(CodeModeResult(it, result)) }.orEmpty(),
    )

    protected fun outer(
        callId: String = "outer-call",
        source: String = "source",
        name: String = CODE_MODE_TOOL_NAME,
    ) = GatewayCustomCall(
        callId = callId,
        name = name,
        input = source,
        raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"$callId","name":"$name","input":"$source"}""",
        ).jsonObject,
    )

    protected fun outerOutcome(callId: String = "outer-call", name: String = CODE_MODE_TOOL_NAME) =
        TurnOutcome.Success(false, false, Usage(), customCalls = listOf(outer(callId, name = name)))

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

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            started.complete(Unit)
            return release.await()
        }

        override fun close() = Unit
    }

    protected class QueuedRuntime(private val queued: ArrayDeque<ArrayDeque<CodeModeStep>>) : CodeModeRuntime {
        val sources = mutableListOf<String>()
        val cells = mutableListOf<ScriptedCell>()
        val starts: Int get() = sources.size

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            sources += source
            return ScriptedCell(queued.removeFirst()).also(cells::add)
        }

        override fun close() = Unit
    }

    protected class ScriptedRuntime(steps: ArrayDeque<CodeModeStep>) : CodeModeRuntime {
        val cell = ScriptedCell(steps)
        var starts = 0

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
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
