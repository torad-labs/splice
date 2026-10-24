package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArrayBuilder
import splice.core.reasoning.HISTORY_POSITION_PREFIX
import splice.dialect.responses.request.BuildOptions

/** Rebuilds positions once per request, never per delta. A digest mismatch is a miss, never a guess.
 * Offsets locate possible boundaries inside text Claude Code coalesced; only a matching digest splits the wire. */
internal class ResponsesHistoryInput(private val opts: BuildOptions) {
    val cursor = ResponsesHistoryCursor(opts.reasoningHistory)
    private val positions = opts.handoff.history().toMutableMap()
    private val offsets = positions.keys.mapNotNull {
        it.removePrefix(HISTORY_POSITION_PREFIX).substringBefore(':').toLongOrNull()
    }.toHashSet()
    private val inject = ResponsesReasoningInject()
    val active: Boolean = positions.isNotEmpty()

    fun inject(sink: JsonArrayBuilder) {
        positions.remove(cursor.history.key).orEmpty().forEach { envelope ->
            opts.handoff.decode(envelope)?.let { inject.addReasoningOnce(sink, it, opts) }
        }
    }

    fun text(sink: JsonArrayBuilder, role: String, text: String, emit: ReasoningTextEmitter) {
        var hashed = 0
        var emitted = 0
        val start = cursor.history.offset
        for (end in 1..text.length) {
            if (start + end !in offsets) continue
            cursor.history.text(role, text.substring(hashed, end))
            hashed = end
            val envelopes = positions[cursor.history.key].orEmpty()
            if (envelopes.isNotEmpty()) {
                emit(text.substring(emitted, end))
                emitted = end
                inject(sink)
            }
        }
        cursor.history.text(role, text.substring(hashed))
        if (emitted < text.length) emit(text.substring(emitted))
    }
}
