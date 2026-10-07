// NEW: projects and rebuilds persisted Codex code-mode history without changing wire item bytes.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.upstream.RoundBody
import java.util.IdentityHashMap

/**
 * Every persisted count, digest and offset is CONVERSATION-relative: the lite preamble (the leading
 * developer-role items — `additional_tools` and the base instructions) is split off before a record
 * is measured and put back after a rewrite. The preamble is environment, not history: Claude Code
 * grows its tool list mid-conversation (ToolSearch loading a deferred schema, an MCP server
 * reconnecting) and its system prompt moves too. The 2026-09-07 live failure was two sessions whose
 * tool count went 35 -> 36 one turn after a completed script; the whole-input digest then refused
 * every later turn with "logical history does not match its persisted baseline".
 */
internal class CodexCodeModeHistoryCodec(private val json: Json) {
    val projection = ResponsesCodeModeProjection()

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? {
        val input = root(bodyJson)?.second ?: return null
        val rawBody = input.dropWhile(::isPreamble)
        val body = conversation(projection.project(input)).body
        return CodeModeInputBoundary(
            fullCount = rawBody.size,
            logicalCount = body.logicalItems.size,
            logicalDigest = digest(JsonArray(body.logicalItems)),
            fullDigest = digest(JsonArray(rawBody)),
            nativeSegments = body.nativeSegments.map {
                CodeModeNativeSegment(it.logicalOffset, it.items)
            },
        )
    }

    fun baselineBoundary(items: List<JsonElement>, record: CodeModeRecord): Int? =
        if (record.metadataVersion == CODE_MODE_METADATA_VERSION && record.replayAnchors != null) {
            CodeModeHistoryIndex(items, this).boundary(record)
        } else {
            record.baselineLogicalCount.takeIf { validPrefix(items, record) }
        }

    /** Splits the projected input into its lite preamble and the conversation the records measure. */
    fun conversation(input: ResponsesCodeModeInput): CodeModeConversation {
        val preamble = input.logicalItems.takeWhile(::isPreamble)
        val offset = preamble.size
        val replay = input.replayItems.map { it.copy(logicalOffset = maxOf(0, it.logicalOffset - offset)) }
        return CodeModeConversation(preamble, ResponsesCodeModeInput(input.logicalItems.drop(offset), replay))
    }

    fun validPrefix(
        items: List<JsonElement>,
        record: CodeModeRecord,
        index: CodeModeHistoryIndex? = null,
    ): Boolean {
        if (record.metadataVersion == CODE_MODE_METADATA_VERSION && record.replayAnchors != null) {
            return (index ?: CodeModeHistoryIndex(items, this)).boundary(record) != null
        }
        if (items.size < record.baselineLogicalCount) return false
        return digest(JsonArray(items.take(record.baselineLogicalCount))) == record.baselineLogicalDigest
    }

    fun validFullPrefix(input: JsonArray, record: CodeModeRecord): Boolean {
        if (record.metadataVersion == CODE_MODE_METADATA_VERSION) return false
        val rawBody = input.dropWhile(::isPreamble)
        if (rawBody.size < record.baselineInputCount) return false
        return digest(JsonArray(rawBody.take(record.baselineInputCount))) == record.baselineInputDigest
    }

    /**
     * Whether [items] hold a record's [continuity] at [at]: item by item, `phase` ignored (V4-336). The
     * one rule for the rewrite that places a record and the reader that finds what the client added.
     * The record's item is the one kept: its preface preceded the outer splice_exec call upstream, so
     * commentary is its true phase, while the client reads final_answer off a message whose script
     * call it never saw (a script that made no client call).
     */
    fun continuityAt(items: List<JsonElement>, at: Int, continuity: List<JsonElement>): Boolean {
        val end = at + continuity.size
        return end <= items.size && items.subList(at, end).map(::phaseless) == continuity.map(::phaseless)
    }

    /** A rewrite that changes nothing is [original] itself; one that changes an item is the new tree, which
     *  writes the bytes its text did (JsonWire.write and JsonWire.string walk the same tree). */
    fun rebuilt(
        root: JsonObject,
        conversation: CodeModeConversation,
        body: ResponsesCodeModeInput,
        original: CodeModeBody? = null,
        emitted: List<CodeModeRecord> = emptyList(),
        omitted: List<CodeModeOmission> = emptyList(),
    ): CodeModeRewrite {
        val offset = conversation.preamble.size
        val joined = ResponsesCodeModeInput(
            conversation.preamble + body.logicalItems,
            body.replayItems.map { it.copy(logicalOffset = it.logicalOffset + offset) },
        )
        val rebuilt = projection.rebuild(joined)
        // Only the same borrowed items in the same order earn the original transport spelling.
        val received = original?.request?.second
        val unchanged = original != null && received != null && rebuilt.size == received.size &&
            rebuilt.indices.all { rebuilt[it] === received[it] }
        if (unchanged) return CodeModeRewrite(original)
        val request = JsonObject(root + (FIELD_INPUT to rebuilt))
        val emission = emitted.takeIf(List<CodeModeRecord>::isNotEmpty)?.let {
            CodeModeEmission(request, omitted.toList(), it)
        }
        return CodeModeRewrite(CodeModeBody(RoundBody.Tree(request), json, emission))
    }

    fun root(bodyJson: String): Pair<JsonObject, JsonArray>? = CodeModeBody(RoundBody.Text(bodyJson), json).request

    fun customOutput(record: CodeModeRecord): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_CUSTOM_OUTPUT)
        put(FIELD_CALL_ID, record.outerCallId)
        put(FIELD_OUTPUT, record.output.orEmpty())
    }

    fun callId(element: JsonElement): String? =
        (element as? JsonObject)?.let { JsonScalars.str(it, FIELD_CALL_ID) }

    fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()

    private fun isPreamble(element: JsonElement): Boolean {
        val item = element as? JsonObject ?: return false
        // V4-390: a mid-conversation context message is developer-role too, but typed; it is history.
        return string(item, CODE_MODE_FIELD_ROLE) == ROLE_DEVELOPER && string(item, FIELD_CALL_ID).isEmpty() &&
            !ResponsesContextMessage.isContext(item)
    }

    private fun phaseless(element: JsonElement): JsonElement =
        (element as? JsonObject)?.let { JsonObject(it - FIELD_PHASE) } ?: element

    private fun digest(value: JsonElement): String = InputDigest.hex(value)
}

/** A streamed call's admitted prefix lives in source until its response terminal certifies the raw item. */
internal object CodeModeCallReplay {
    fun item(record: CodeModeRecord): JsonObject =
        if (
            record.sourceState?.complete == true ||
            JsonScalars.strOrEmpty(record.outer[FIELD_INPUT]) == record.source
        ) {
            record.outer
        } else {
            JsonObject(record.outer + (FIELD_INPUT to JsonPrimitive(record.source)))
        }
}

/** The lite preamble a request arrived with, and the conversation body every record is measured on. */
internal data class CodeModeConversation(
    val preamble: List<JsonElement>,
    val body: ResponsesCodeModeInput,
)

/** One round's request as code mode reads it: [round] is what it posts, and [request] is what every reader
 *  of the round reads. An unchanged history posts [round] as it arrived. */
internal class CodeModeBody(val round: RoundBody, json: Json, val emission: CodeModeEmission? = null) {
    /** The body parsed once: its root and input array, or null when it is not a Responses request. A tree
     *  is read as itself; only text is parsed. */
    val request: Pair<JsonObject, JsonArray>? = run {
        val root = when (round) {
            is RoundBody.Tree -> round.element
            is RoundBody.Text -> json.parseToJsonElement(round.text)
        } as? JsonObject
        val input = root?.get(FIELD_INPUT) as? JsonArray
        if (root != null && input != null) root to input else null
    }
}

/** The exact emitted tree preserves earlier placements and omissions; only new records can append a tail. */
internal class CodeModeEmission(
    private val request: JsonObject,
    val omitted: List<CodeModeOmission>,
    records: List<CodeModeRecord>,
) {
    private val owners = IdentityHashMap<CodeModeRecord, Unit>().apply {
        records.forEach { put(it, Unit) }
    }

    fun previous(request: JsonObject, records: List<CodeModeRecord>): CodeModeEmission? {
        if (this.request !== request) return null
        val incoming = IdentityHashMap<CodeModeRecord, Unit>().apply { records.forEach { put(it, Unit) } }
        return takeIf { owners.keys.all(incoming::containsKey) }
    }

    fun processed(record: CodeModeRecord): Boolean = owners.containsKey(record)
}

internal const val CODE_MODE_FIELD_CALL_ID = "call_id"
internal const val CODE_MODE_FIELD_TYPE = "type"
internal const val CODE_MODE_FIELD_ROLE = "role"
private const val FIELD_INPUT = "input"
private const val FIELD_OUTPUT = "output"
private const val ROLE_DEVELOPER = "developer"
private const val FIELD_PHASE = "phase"
private const val FIELD_CALL_ID = CODE_MODE_FIELD_CALL_ID
private const val FIELD_TYPE = CODE_MODE_FIELD_TYPE
internal const val TYPE_CUSTOM_OUTPUT = "custom_tool_call_output"
