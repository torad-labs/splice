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
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.upstream.codemode.CodeModeManual
import java.util.concurrent.ConcurrentHashMap

internal class CodexCodeModeWire(private val json: Json, private val log: LogSink) {
    private val history = CodexCodeModeHistory(json)
    private val namespace = ResponsesFunctionNamespace()

    /** Record ids whose omission was already logged — one line per record, not one per turn. */
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Client tool names already logged as losing a code-mode name collision. */
    private val announcedCollisions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** V4-388: codex's code_mode_only surface (codex-rs core/src/tools/spec_plan.rs
     *  is_hidden_by_code_mode_only): every client function tool leaves the top level and is rendered
     *  into `exec`'s manual, and tool_search leaves with them — codex's own test sends exec, wait,
     *  request_user_input and web_search with deferred tools on (core/tests/suite/code_mode.rs:2805-2820).
     *  [clientTools] is the whole client catalog, so a tool the deferred surface withheld gets the
     *  manual's deferred note and is found and called through `ALL_TOOLS` and `tools`. The lite list
     *  arrives grouped into the `functions` namespace and leaves grouped the same way (V4-390): exec
     *  rides inside it, as codex's does. */
    fun injectTool(request: JsonObject, clientTools: Set<String>): JsonObject {
        val input = request[FIELD_INPUT] as? JsonArray ?: return request
        val index = input.indexOfFirst { string(it as? JsonObject, FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
        val item = input.getOrNull(index) as? JsonObject
        val tools = item?.get(FIELD_TOOLS) as? JsonArray
        require(item != null && tools != null) { "code mode requires the Responses lite additional_tools item" }
        val (functions, hosted) = namespace.members(tools).partition {
            string(it as? JsonObject, FIELD_TYPE) == TYPE_FUNCTION
        }
        val reachable = reachable(clientTools)
        val nested = functions.mapNotNull { it as? JsonObject }.map { tool ->
            CodeModeManual.NestedTool(string(tool, FIELD_NAME), string(tool, FIELD_DESCRIPTION), tool[FIELD_PARAMETERS])
        }.filter { it.name in reachable }
        val deferred = (reachable - nested.map(CodeModeManual.NestedTool::name).toSet()).isNotEmpty()
        val beside = hosted.filterNot { string(it as? JsonObject, FIELD_TYPE) == TYPE_TOOL_SEARCH }
        val exec = execTool(CodeModeManual.description(nested, deferred))
        val surface = namespace.group(JsonArray(listOf(exec) + beside))
        val replaced = JsonObject(item + (FIELD_TOOLS to surface))
        val rebuilt = input.mapIndexed { position, element -> if (position == index) replaced else element }
        return JsonObject(request + (FIELD_INPUT to JsonArray(rebuilt)))
    }

    /** The body a code-mode round posts upstream: the client's history minus the tool_search pairs the
     *  dialect replays for deferred tools and a record's native searches, since exec declares no
     *  tool_search beside it. Records, digests and baselines keep reading the client's own history;
     *  only the posted bytes change, the same way on every round, so the prompt cache prefix holds. */
    fun upstream(bodyJson: String): String {
        val request = json.parseToJsonElement(bodyJson) as? JsonObject ?: return bodyJson
        val input = request[FIELD_INPUT] as? JsonArray ?: return bodyJson
        val kept = input.filterNot { string(it as? JsonObject, FIELD_TYPE) in TOOL_SEARCH_ITEMS }
        return if (kept.size == input.size) {
            bodyJson
        } else {
            json.encodeToString(JsonObject.serializer(), JsonObject(request + (FIELD_INPUT to JsonArray(kept))))
        }
    }

    /** The client tools `tools.<Name>` can reach: the worker dedupes the same whole catalog with the
     *  same rule, so the manual never documents a global the cell binds to another tool. A tool whose
     *  normalized name another took is logged once, as codex warns (core/src/tools/spec_plan.rs). */
    private fun reachable(clientTools: Set<String>): Set<String> {
        val kept = CodeModeManual.nestedNames(clientTools).toSet()
        (clientTools - kept).filter { announcedCollisions.add(it) }.forEach { name ->
            val global = CodeModeManual.identifier(name)
            log("[code-mode] skipping client tool '$name': its code-mode name $global is taken")
        }
        return kept
    }

    fun anchoredBoundary(bodyJson: String, completed: List<CodeModeRecord>): CodeModeInputBoundary? =
        history.anchoredBoundary(bodyJson, completed)

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
) {
    var replayAnchors: splice.provider.codex.state.CodeModeReplayAnchors? = null
}

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
 *  continuity has none, so the client's preface never matches it and would be placed twice.
 *  v5: minted ids and local continuity anchors place independently of older canonical rewrites.
 *  v4 records keep their digest-based compatibility path until retention removes them. */
internal const val CODE_MODE_LEGACY_METADATA_VERSION = 4

// why: v5 placements are independently anchored; v4 keeps its persisted-prefix compatibility reader.
internal const val CODE_MODE_METADATA_VERSION = 5
private const val RECORD_ID_LOG_CHARS = 8
private const val FIELD_INPUT = "input"
private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_TOOLS = "tools"
private const val FIELD_PARAMETERS = "parameters"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private const val TYPE_FUNCTION = "function"
private const val TYPE_TOOL_SEARCH = "tool_search"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
private val TOOL_SEARCH_ITEMS = setOf(TYPE_TOOL_SEARCH_CALL, TYPE_TOOL_SEARCH_OUTPUT)
