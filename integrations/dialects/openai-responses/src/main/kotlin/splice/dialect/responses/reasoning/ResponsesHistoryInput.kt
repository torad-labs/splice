package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArrayBuilder
import splice.core.reasoning.HISTORY_POSITION_PREFIX
import splice.dialect.responses.request.BuildOptions

/** Rebuilds positions once per request, never per delta. A digest mismatch is a miss, never a guess.
 * Offsets locate possible boundaries inside text Claude Code coalesced; only a matching digest splits the wire. */
internal class ResponsesHistoryInput(private val opts: BuildOptions) {
    val cursor = ResponsesHistoryCursor(opts.reasoningHistory, opts.handoff.resultProofs())
    private val positions = opts.handoff.history().toMutableMap()
    private val offsets = positions.keys.mapNotNull {
        it.removePrefix(HISTORY_POSITION_PREFIX).substringBefore(':').toLongOrNull()
    }.toHashSet()
    private val inject = ResponsesReasoningInject()
    private val aliases = linkedMapOf<String, String>()
    val active: Boolean = positions.isNotEmpty()

    fun inject(sink: JsonArrayBuilder) {
        val stable = cursor.history.key
        val current = positions.remove(stable).orEmpty()
        val legacy = cursor.legacyKey
        val previous = if (legacy == stable) emptyList() else positions.remove(legacy).orEmpty()
        if (previous.isNotEmpty()) aliases[legacy] = stable
        (previous + current).forEach { envelope ->
            opts.handoff.decode(envelope)?.let { inject.addReasoningOnce(sink, it, opts) }
        }
    }

    /** One promotion batch after all matching positions have been reconstructed, never one rewrite per item. */
    fun promote() {
        if (aliases.isEmpty() && cursor.pendingProofs.isEmpty()) return
        opts.handoff.historyAlias(ReasoningHistoryUpdate(aliases.toMap(), cursor.pendingProofs.toMap()))
        aliases.clear()
        cursor.pendingProofs.clear()
    }

    fun text(sink: JsonArrayBuilder, role: String, text: String, emit: ReasoningTextEmitter) {
        var hashed = 0
        var emitted = 0
        val start = cursor.history.offset
        for (end in 1..text.length) {
            if (start + end !in offsets) continue
            cursor.text(role, text.substring(hashed, end))
            hashed = end
            if (positions.containsKey(cursor.history.key) || positions.containsKey(cursor.legacyKey)) {
                emit(text.substring(emitted, end))
                emitted = end
                inject(sink)
            }
        }
        cursor.text(role, text.substring(hashed))
        if (emitted < text.length) emit(text.substring(emitted))
    }
}
