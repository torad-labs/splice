package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CodeModeIssuedStep
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/** A source's post-exec prose can be joined to its preface, but its exact delivered step still owns that echo. */
internal class CodeModeDeliveredEchoes(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
) {
    fun indexes(items: List<JsonElement>, record: CodeModeRecord, boundary: Int): Set<Int> =
        if (record.carry.nativeAfterExec.isNullOrEmpty()) {
            emptySet()
        } else {
            record.issued.flatMap { step(items, it, boundary)?.toList().orEmpty() }.toSet()
        }

    fun withoutAfterExec(items: List<JsonElement>, records: Collection<CodeModeRecord>): List<JsonElement> {
        val sources = records.filter { !it.carry.nativeAfterExec.isNullOrEmpty() }
        if (sources.isEmpty()) return items
        val input = codec.projection.project(JsonArray(items))
        val removed = mutableSetOf<Int>()
        val native = mutableMapOf<Int, MutableSet<JsonElement>>()
        val digests = InputItemDigests()
        sources.forEach { record ->
            val lower = codec.baselineBoundary(input.logicalItems, record, digests) ?: 0
            record.issued.forEach issuedStep@{ issued ->
                val echo = step(input.logicalItems, issued, lower) ?: return@issuedStep
                removed += echo
                val owned = record.carry.source().afterExec.replayItems.flatMap { it.items }
                    .map(CodeModeReasoningIdentity::key)
                for (at in echo.first..echo.last + 1) {
                    native.getOrPut(at) { mutableSetOf() }.addAll(owned)
                }
            }
        }
        return rebuild(input, removed, native)
    }

    private fun step(items: List<JsonElement>, step: CodeModeIssuedStep, lower: Int): IntRange? {
        val text = step.deliveredText?.takeIf(String::isNotEmpty) ?: return null
        val ids = step.calls.mapTo(mutableSetOf()) { it.clientId }
        val callback = items.indexOfFirst {
            codec.string(it as? JsonObject, "type") == "function_call" && codec.callId(it) in ids
        }
        return if (callback < 0) null else echo(items, callback, text, lower)
    }

    private fun echo(items: List<JsonElement>, callback: Int, text: String, lower: Int): IntRange? {
        val floor = maxOf(lower, items.take(callback).indexOfLast { scripts.text(it) == null } + 1)
        val expected = listOf(ResponsesAssistantText.item(text, AssistantPhase.COMMENTARY))
        val start = (floor until callback).firstOrNull { at ->
            scripts.echo(items, at, expected)?.let { it.end == callback && it.split == 0 } == true
        }
        return start?.let { it until callback }
    }

    private fun rebuild(
        input: ResponsesCodeModeInput,
        removed: Set<Int>,
        native: Map<Int, Set<JsonElement>>,
    ): List<JsonElement> {
        val offsets = IntArray(input.logicalItems.size + 1)
        var count = 0
        offsets.indices.forEach { at ->
            offsets[at] = count
            if (at < input.logicalItems.size && at !in removed) count++
        }
        val replay = input.replayItems.mapNotNull { segment ->
            val items = segment.items.filterNot {
                CodeModeReasoningIdentity.key(
                    it,
                ) in native[segment.logicalOffset].orEmpty()
            }
            segment.copy(logicalOffset = offsets[segment.logicalOffset], items = items).takeIf { it.items.isNotEmpty() }
        }
        return codec.projection.rebuild(
            ResponsesCodeModeInput(input.logicalItems.filterIndexed { at, _ -> at !in removed }, replay),
        )
    }
}
