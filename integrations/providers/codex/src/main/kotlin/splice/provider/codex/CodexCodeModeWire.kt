// NEW: owns code-mode wire injection, history rewriting, and same-round continuity encoding.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.request.ResponsesCallPairing
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.dialect.responses.request.ResponsesPriorReasoning
import splice.provider.codex.history.CodeModeContinuityCapture
import splice.provider.codex.history.CodexCodeModeHistory
import splice.provider.codex.state.diagnostics.CodeModeHistoryOmissions
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.state.diagnostics.CodeModeNativeRetirement
import splice.provider.codex.surface.CodeModeToolSurface
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeManual

internal class CodexCodeModeWire(
    private val json: Json,
    private val log: LogSink,
    retireNative: CodeModeNativeRetirement = CodeModeNativeRetirement {},
    private val replayReasoning: CodeModeReplayReasoning = CodeModeReplayReasoning { true },
) {
    private val history = CodexCodeModeHistory(json)
    private val priorReasoning = ResponsesPriorReasoning()
    private val pairing = ResponsesCallPairing()
    private val toolSurface = CodeModeToolSurface(log)

    private val omissions = CodeModeHistoryOmissions(log, retireNative)

    fun injectTool(
        request: JsonObject,
        clientTools: Set<String>,
        policy: CodeModeToolPolicy = CodeModeToolPolicy(),
    ): JsonObject = toolSurface.injectTool(request, clientTools, policy)

    /** The round as code mode reads it: parsed once here, and read as that one tree after. */
    fun body(round: RoundBody): CodeModeBody = CodeModeBody(round, json)

    /** The body a code-mode round posts upstream: the client's history minus the tool_search pairs the
     *  dialect replays for deferred tools and a record's native searches, since exec declares no
     *  tool_search beside it. Records, digests and baselines keep reading the client's own history;
     *  only the posted bytes change, the same way on every round, so the prompt cache prefix holds.
     *  A body with none goes out as the body it is; one with some keeps the spelling it always had. However the
     *  history was rebuilt (an interrupted round, a record the rewrite could not place), each call goes out once and
     *  answered, and each reasoning item once, as Codex sends its history: the backend refuses a call with no
     *  result. */
    fun upstream(body: CodeModeBody): RoundBody {
        val (request, input) = body.request ?: return body.round
        val searched = input.filterNot { string(it as? JsonObject, FIELD_TYPE) in TOOL_SEARCH_ITEMS }
        // replay_reasoning=false: the stored continuity of earlier scripts does not ride back; this turn's does.
        val kept = JsonArray(if (replayReasoning.now()) searched else priorReasoning.withoutPriorTurns(searched))
        val paired = pairing.pair(kept)
        return if (paired === kept && kept.size == input.size) {
            body.round
        } else {
            RoundBody.Text(json.encodeToString(JsonObject.serializer(), JsonObject(request + (FIELD_INPUT to paired))))
        }
    }

    fun anchoredBoundary(body: CodeModeBody, completed: List<CodeModeRecord>): CodeModeInputBoundary? =
        history.anchoredBoundary(body, completed)

    /** An owned call can be present before its result arrives. Keep both wire forms as owner evidence;
     *  a call from a completed record is not an ACTIVE owner's id and cannot claim it. */
    fun callbackIds(body: CodeModeBody): Set<String> {
        val input = body.request?.second ?: return emptySet()
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
        body: CodeModeBody,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
        report: CodeModeExtra? = null,
        clearedCallbacks: Set<String> = emptySet(),
    ): CodeModeExtra = if (report == null) {
        history.extraContent(body, record, candidateMedia, clearedCallbacks)
    } else {
        log(history.describeExtraContent(body, record, candidateMedia, report, clearedCallbacks))
        report
    }

    fun canonicalize(
        body: CodeModeBody,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>> = emptyMap(),
        capture: CodeModeRecord? = null,
    ): CodeModeRewrite {
        val rewrite = history.canonicalize(body, records, replayMedia, capture)
        omissions.observe(rewrite)
        return rewrite
    }

    fun restoreBaseline(body: CodeModeBody, record: CodeModeRecord): CodeModeRewrite =
        history.restoreBaseline(body, record)

    fun continuity(outcome: TurnOutcome.Success): CodeModeContinuity = CodeModeContinuityCapture.success(outcome)

    private fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()
}

internal data class CodeModeContinuity(
    val logicalItems: List<JsonElement>,
    val replayItems: List<CodeModeNativeSegment>,
    val nativeAfterExec: List<JsonElement>? = null,
) {
    val afterExec by lazy { ResponsesCodeModeProjection().project(JsonArray(nativeAfterExec.orEmpty())) }

    val nativeReplay: List<CodeModeNativeSegment>
        get() = replayItems + afterExec.replayItems.map {
            CodeModeNativeSegment(logicalItems.size + 1 + it.logicalOffset, it.items)
        }
}

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
    val body: CodeModeBody?,
    val error: String? = null,
    val omitted: List<CodeModeOmission> = emptyList(),
    val nativeRejection: CodeModeNativeRejection? = null,
) {
    /** The rewritten request as text, rendered when read. Rounds post [body] and never read this. */
    val bodyJson: String? get() = body?.round?.text
}

/** A completed record the rewrite could not place; the reason is what the digest check reported. */
internal data class CodeModeOmission(
    val record: CodeModeRecord,
    val reason: String,
    val nativeRejection: CodeModeNativeRejection? = null,
)

internal const val CODE_MODE_TOOL_NAME = CodeModeManual.TOOL_NAME
internal const val CODE_MODE_WAIT_TOOL_NAME = "wait"

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
private const val FIELD_INPUT = "input"
private const val FIELD_TYPE = "type"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
private val TOOL_SEARCH_ITEMS = setOf(TYPE_TOOL_SEARCH_CALL, TYPE_TOOL_SEARCH_OUTPUT)

/**
 * Whether an earlier turn's reasoning rides back to the model, asked at each post so a change acts on the next
 * round.
 */
public fun interface CodeModeReplayReasoning {
    public fun now(): Boolean
}
