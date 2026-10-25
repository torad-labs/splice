package splice.provider.codex.branch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeClientAcknowledgement
import splice.provider.codex.history.CodeModeClientAcknowledgements
import splice.provider.codex.state.CodeModeNativeChain

/** A client start can itself contain earlier cleared results; only proved earlier clears may acknowledge it. */
internal class CodeModeClearedBaseline(
    private val codec: CodexCodeModeHistoryCodec,
    items: List<JsonElement>,
) {
    private val projected by lazy { codec.projection.project(JsonArray(items)) }

    fun matchesAcknowledgedStart(
        record: CodeModeRecord,
        callback: String,
        cleared: Set<String>,
        posted: CodeModeClientAcknowledgement? = null,
    ): Boolean {
        val start = record.replayAnchors?.client?.acknowledgement ?: posted ?: return false
        val cursor = start.consume(projected, cleared) ?: return false
        val end = cursor.item + if (cursor.characters == 0) 0 else 1
        val called = projected.logicalItems.indexOfFirst { codec.callId(it) == callback }
        return called >= end && matchesNativeStart(record, start, cursor.item)
    }

    private fun matchesNativeStart(
        record: CodeModeRecord,
        start: CodeModeClientAcknowledgement,
        boundary: Int,
    ): Boolean {
        val own = CodeModeNativeChain.continuity(record).flatMap { it.items }
            .mapTo(mutableSetOf(), CodeModeClientAcknowledgements::identity)
        if (start.native.any { it in own }) return false
        val native = projected.replayItems.filter { it.logicalOffset <= boundary }.flatMap { it.items }
            .map(CodeModeClientAcknowledgements::identity).filterNot { it in own }
        return native == start.native
    }
}
