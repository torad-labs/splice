// NEW: accepted results and their rendered follow-ups share one ordered mutable owner.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeAcceptedResult
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeResultSnapshot
import splice.upstream.codemode.CodeModeResult

/** Acceptance order is durable sequence order. A result and its media never disagree on keys. */
internal class CodeModeAccepted(entries: Map<String, CodeModeAcceptedResult> = emptyMap()) {
    private val entries: MutableMap<String, CodeModeAcceptedResult> = LinkedHashMap(entries)

    val results: Map<String, CodeModeResult> get() = entries.mapValues { (_, entry) -> entry.result }

    /** Null means legacy uncaptured media; empty means captured with no media. */
    fun media(id: String): List<JsonElement>? = entries[id]?.media

    fun durableMedia(): List<JsonElement> = entries.values.flatMap { it.media.orEmpty() }

    /** A supplied id without media is explicitly captured as having none, never left legacy. */
    fun accept(supplied: Map<String, CodeModeResult>, media: Map<String, List<JsonElement>>) {
        supplied.forEach { (id, result) -> entries[id] = CodeModeAcceptedResult(result, media[id].orEmpty()) }
    }

    /** What accepting [supplied] adds to the record, at stored width. */
    fun heapGrowth(supplied: Map<String, CodeModeResult>, media: Map<String, List<JsonElement>>): Long =
        supplied.entries.sumOf { (id, result) ->
            val prior = entries[id]
            val before = prior?.let { acceptedBytes(it.result, it.media.orEmpty()) } ?: 0L
            val after = acceptedBytes(result, media[id].orEmpty()) +
                if (prior == null) CodeModeWeight.STORED.text(id) else 0L
            (after - before).coerceAtLeast(0L)
        }

    /** What [snapshot] still holds of the prior results that accepting [supplied] replaces. */
    fun kept(snapshot: CodeModeRecordSnapshot, supplied: Map<String, CodeModeResult>): Long =
        supplied.keys.sumOf { id ->
            val prior = entries[id]
            val held = snapshot.results[id]
            if (prior == null || held == null) return@sumOf 0L
            val output = if (held.output === prior.result.output) CodeModeWeight.STORED.text(held.output) else 0L
            output + if (held.media === prior.media) held.media.orEmpty().sumOf(CodeModeWeight.STORED::json) else 0L
        }

    private fun acceptedBytes(result: CodeModeResult, media: List<JsonElement>): Long =
        CodeModeWeight.STORED.text(result.output) + media.sumOf(CodeModeWeight.STORED::json)

    fun copy(): CodeModeAccepted = CodeModeAccepted(entries)

    fun restore(prior: CodeModeAccepted) {
        entries.clear()
        entries.putAll(prior.entries)
    }

    /** Rehydrate durable order and the distinction between uncaptured and captured-empty media. */
    fun restore(saved: Map<String, CodeModeResultSnapshot>) {
        entries.clear()
        saved.forEach { (id, value) -> entries[id] = value.restore(id) }
    }

    fun snapshot(): Map<String, CodeModeResultSnapshot> = entries.mapValues { (_, entry) ->
        CodeModeResultSnapshot(entry.result.output, entry.result.isError, entry.media)
    }
}
