// NEW: owns code-mode wire injection, history rewriting, and same-round continuity encoding.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.upstream.codemode.CodeModeManual
import java.util.concurrent.ConcurrentHashMap

internal class CodexCodeModeWire(private val json: Json, private val log: LogSink) {
    private val history = CodexCodeModeHistory(json)

    /** Record ids whose omission was already logged — one line per record, not one per turn. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** V4-388: codex's code_mode_only surface (codex-rs core/src/tools/spec_plan.rs
     *  is_hidden_by_code_mode_only): every client function tool leaves the top level and is rendered
     *  into `exec`'s manual; a hosted tool (tool_search) stays beside it. [clientTools] is the whole
     *  client catalog, so a tool the deferred surface withheld gets the manual's deferred note and
     *  stays callable through `tools`. */
    fun injectTool(request: JsonObject, clientTools: Set<String>): JsonObject {
        val input = request[FIELD_INPUT] as? JsonArray ?: return request
        val index = input.indexOfFirst { string(it as? JsonObject, FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
        val item = input.getOrNull(index) as? JsonObject
        val tools = item?.get(FIELD_TOOLS) as? JsonArray
        require(item != null && tools != null) { "code mode requires the Responses lite additional_tools item" }
        val (functions, hosted) = tools.partition { string(it as? JsonObject, FIELD_TYPE) == TYPE_FUNCTION }
        val nested = functions.mapNotNull { it as? JsonObject }.map { tool ->
            CodeModeManual.NestedTool(string(tool, FIELD_NAME), string(tool, FIELD_DESCRIPTION), tool[FIELD_PARAMETERS])
        }
        val deferred = (clientTools - nested.map(CodeModeManual.NestedTool::name).toSet()).isNotEmpty()
        val surface = JsonArray(listOf(execTool(CodeModeManual.description(nested, deferred))) + hosted)
        val replaced = JsonObject(item + (FIELD_TOOLS to surface))
        val rebuilt = input.mapIndexed { position, element -> if (position == index) replaced else element }
        return JsonObject(request + (FIELD_INPUT to JsonArray(rebuilt)))
    }

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? = history.inputBoundary(bodyJson)

    /** An owned call can be present before its result arrives. Keep both wire forms as owner evidence;
     *  a call from a completed record is not an ACTIVE owner's id and cannot claim it. */
    fun callbackIds(bodyJson: String): Set<String> {
        val input = (json.parseToJsonElement(bodyJson) as? JsonObject)?.get(FIELD_INPUT) as? JsonArray
            ?: return emptySet()
        return input.mapNotNull { element ->
            val item = element as? JsonObject
            when (string(item, FIELD_TYPE)) {
                "function_call", "function_call_output" ->
                    string(item, CODE_MODE_FIELD_CALL_ID).takeIf(String::isNotEmpty)
                else -> null
            }
        }.toSet()
    }

    /** [candidateMedia]: this turn's rendered follow-ups for result ids the record does not hold
     *  yet — owned on sight, so a screenshot arriving for a parked script resumes it. */
    fun extraContent(
        bodyJson: String,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeExtra = history.extraContent(bodyJson, record, candidateMedia)

    fun canonicalize(
        bodyJson: String,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeRewrite {
        val rewrite = history.canonicalize(bodyJson, records, replayMedia)
        rewrite.omitted.filter { announced.add(it.record.id) }.forEach { omission ->
            log(
                "[code-mode] history rewrite skipped record ${omission.record.id.take(RECORD_ID_LOG_CHARS)} " +
                    "(outer ${omission.record.outerCallId}): ${omission.reason}; its client calls stay in " +
                    "the history as ordinary tool calls",
            )
        }
        return rewrite
    }

    fun restoreBaseline(bodyJson: String, record: CodeModeRecord): CodeModeRewrite =
        history.restoreBaseline(bodyJson, record)

    fun continuity(outcome: TurnOutcome.Success): CodeModeContinuity {
        val items = buildList {
            addAll(outcome.reasoningEnvelopes.mapNotNull(ReasoningReplay::decodeReasoningEnvelope))
            // V4-335: the text said before the script, as the client replays it: it precedes the
            // client calls in the same message, which the builder calls commentary.
            if (outcome.emittedText && outcome.bodyText.isNotEmpty()) {
                add(ResponsesAssistantText.item(outcome.bodyText, AssistantPhase.COMMENTARY))
            }
        }
        val projected = ResponsesCodeModeProjection().project(JsonArray(items))
        return CodeModeContinuity(
            projected.logicalItems,
            projected.replayItems.map { CodeModeNativeSegment(it.logicalOffset, it.items) },
        )
    }

    /** codex-rs execute_spec.rs create_code_mode_tool: a freeform tool constrained by the exec grammar. */
    private fun execTool(description: String): JsonObject = buildJsonObject {
        put(FIELD_TYPE, "custom")
        put(FIELD_NAME, CODE_MODE_TOOL_NAME)
        put(FIELD_DESCRIPTION, description)
        put(
            "format",
            buildJsonObject {
                put(FIELD_TYPE, "grammar")
                put("syntax", "lark")
                put("definition", CodeModeManual.GRAMMAR)
            },
        )
    }

    private fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()
}

internal data class CodeModeContinuity(
    val logicalItems: List<JsonElement>,
    val replayItems: List<CodeModeNativeSegment>,
)

internal data class CodeModeInputBoundary(
    val fullCount: Int,
    val logicalCount: Int,
    val logicalDigest: String,
    val fullDigest: String,
    val nativeSegments: List<CodeModeNativeSegment>,
)

internal data class CodeModeRewrite(
    val bodyJson: String?,
    val error: String? = null,
    val omitted: List<CodeModeOmission> = emptyList(),
)

/** A completed record the rewrite could not place; the reason is what the digest check reported. */
internal data class CodeModeOmission(val record: CodeModeRecord, val reason: String)

internal const val CODE_MODE_TOOL_NAME = CodeModeManual.TOOL_NAME

/** The outer tool's name before V4-388: a live conversation's history and a parked record still carry
 *  it, so its calls are still this bridge's. */
internal const val LEGACY_CODE_MODE_TOOL_NAME = "splice_exec"

/** v3 (2026-09-07): counts, digests and native offsets are conversation-relative (lite preamble
 *  excluded). A v2 record's numbers point into the whole input, so it is never re-placed: a
 *  completed one is omitted from the rewrite, an unfinished one is LOST.
 *  v4 (2026-09-26, V4-335): continuity carries the phase the client replays. A v3 record's
 *  continuity has none, so the client's preface never matches it and would be placed twice. */
internal const val CODE_MODE_METADATA_VERSION = 4
private const val RECORD_ID_LOG_CHARS = 8
private const val FIELD_INPUT = "input"
private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_TOOLS = "tools"
private const val FIELD_PARAMETERS = "parameters"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private const val TYPE_FUNCTION = "function"
