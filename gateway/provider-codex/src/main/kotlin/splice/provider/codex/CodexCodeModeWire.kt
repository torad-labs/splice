// NEW: owns code-mode wire injection, history rewriting, and same-round continuity encoding.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesCodeModeProjection
import java.util.concurrent.ConcurrentHashMap

internal class CodexCodeModeWire(private val json: Json, private val log: LogSink) {
    private val history = CodexCodeModeHistory(json)

    /** Record ids whose omission was already logged — one line per record, not one per turn. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun injectTool(request: JsonObject): JsonObject {
        val input = request[FIELD_INPUT] as? JsonArray ?: return request
        var injected = false
        val replaced = buildJsonArray {
            input.forEach { element ->
                val item = element as? JsonObject
                if (!injected && string(item, FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS) {
                    val tools = item?.get(FIELD_TOOLS) as? JsonArray
                    if (tools != null) {
                        add(JsonObject(item + (FIELD_TOOLS to JsonArray(tools + customTool()))))
                        injected = true
                    } else {
                        add(element)
                    }
                } else {
                    add(element)
                }
            }
        }
        require(injected) { "code mode requires the Responses lite additional_tools item" }
        return JsonObject(request + (FIELD_INPUT to replaced))
    }

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? = history.inputBoundary(bodyJson)

    fun hasExtraContent(bodyJson: String, record: CodeModeRecord): Boolean =
        history.hasExtraContent(bodyJson, record)

    fun canonicalize(bodyJson: String, records: List<CodeModeRecord>): CodeModeRewrite {
        val rewrite = history.canonicalize(bodyJson, records)
        rewrite.omitted.filter { announced.add(it.record.id) }.forEach { omission ->
            log(
                "[code-mode] history rewrite skipped record ${omission.record.id.take(RECORD_ID_LOG_CHARS)} " +
                    "(outer ${omission.record.outerCallId}): ${omission.reason} — its client calls stay in " +
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
            if (outcome.emittedText && outcome.bodyText.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put(FIELD_ROLE, ROLE_ASSISTANT)
                        put(FIELD_CONTENT, outcome.bodyText)
                    },
                )
            }
        }
        val projected = ResponsesCodeModeProjection().project(JsonArray(items))
        return CodeModeContinuity(
            projected.logicalItems,
            projected.replayItems.map { CodeModeNativeSegment(it.logicalOffset, it.items) },
        )
    }

    private fun customTool(): JsonObject = buildJsonObject {
        put(FIELD_TYPE, "custom")
        put(FIELD_NAME, CODE_MODE_TOOL_NAME)
        put(FIELD_DESCRIPTION, CODE_MODE_TOOL_DESCRIPTION)
        put("format", buildJsonObject { put(FIELD_TYPE, "text") })
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

internal const val CODE_MODE_TOOL_NAME = "splice_exec"

/** v3 (2026-09-07): counts, digests and native offsets are conversation-relative (lite preamble
 *  excluded). A v2 record's numbers point into the whole input, so it is never re-placed: a
 *  completed one is omitted from the rewrite, an unfinished one is LOST. */
internal const val CODE_MODE_METADATA_VERSION = 3
private const val RECORD_ID_LOG_CHARS = 8
private const val FIELD_CONTENT = "content"
private const val FIELD_INPUT = "input"
private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_TOOLS = "tools"
private const val FIELD_ROLE = "role"
private const val ROLE_ASSISTANT = "assistant"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private const val CODE_MODE_TOOL_DESCRIPTION =
    "Execute one bounded JavaScript cell with await and tools.call('name', args). " +
        "Tool calls resolve to their original output strings; use console.log for final output. " +
        "The cell persists only while awaiting its calls and has no filesystem or network access."
