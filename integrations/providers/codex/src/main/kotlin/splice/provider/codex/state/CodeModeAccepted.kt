// NEW: accepted results and their rendered follow-ups share one ordered mutable owner.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeAcceptedResult
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeResultSnapshot
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import java.util.Collections

/** Acceptance order is durable sequence order. A result and its media never disagree on keys. */
internal class CodeModeAccepted(entries: Map<String, CodeModeAcceptedResult> = emptyMap()) {
    private val entries: MutableMap<String, CodeModeAcceptedResult> = LinkedHashMap(entries)

    val results: Map<String, CodeModeResult> get() = entries.mapValues { (_, entry) -> entry.result }

    /** The accepted ids in acceptance order, as a read-only view: read it now, never keep it. */
    fun ids(): Set<String> = Collections.unmodifiableSet(entries.keys)

    fun holds(id: String): Boolean = id in entries

    /** Whether any of [ids] was accepted, walking the smaller side so neither a long turn nor a long record copies. */
    fun holdsAny(ids: Set<String>): Boolean =
        if (ids.size < entries.size) ids.any { it in entries } else entries.keys.any { it in ids }

    /** Null means legacy uncaptured media; empty means captured with no media. */
    fun media(id: String): List<JsonElement>? = entries[id]?.media

    fun clientOutputDigest(id: String): String? = entries[id]?.clientOutputDigest

    fun durableMedia(): List<JsonElement> = entries.values.flatMap { it.media.orEmpty() }

    /** A supplied id without media is explicitly captured as having none, never left legacy. */
    fun accept(
        supplied: Map<String, CodeModeResult>,
        media: Map<String, List<JsonElement>>,
        clientOutputDigests: Map<String, String> = emptyMap(),
    ) {
        supplied.forEach { (id, result) ->
            entries[id] = CodeModeAcceptedResult(result, media[id].orEmpty(), clientOutputDigests[id])
        }
    }

    /** What accepting [supplied] adds to the record, at stored width. */
    fun heapGrowth(
        supplied: Map<String, CodeModeResult>,
        media: Map<String, List<JsonElement>>,
        clientOutputDigests: Map<String, String> = emptyMap(),
    ): Long =
        supplied.entries.sumOf { (id, result) ->
            val prior = entries[id]
            val before = prior?.let { acceptedBytes(it.result, it.media.orEmpty(), it.clientOutputDigest) } ?: 0L
            val after = acceptedBytes(result, media[id].orEmpty(), clientOutputDigests[id]) +
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
            val media = if (held.media === prior.media) held.media.orEmpty().sumOf(CodeModeWeight.STORED::json) else 0L
            val proof = CodeModeWeight.STORED.clientOutputDigest(
                held.clientOutputDigest?.takeIf { it === prior.clientOutputDigest },
            )
            val payload = held.payload?.takeIf { it === prior.result.payload }?.let(CodeModeWeight.STORED::json) ?: 0L
            output + media + proof + payload
        }

    private fun acceptedBytes(result: CodeModeResult, media: List<JsonElement>, clientOutputDigest: String?): Long =
        CodeModeWeight.STORED.text(result.output) + media.sumOf(CodeModeWeight.STORED::json) +
            CodeModeWeight.STORED.clientOutputDigest(clientOutputDigest) +
            (result.payload?.let(CodeModeWeight.STORED::json) ?: 0L)

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
        CodeModeResultSnapshot(
            entry.result.output,
            entry.result.isError,
            entry.media,
            entry.clientOutputDigest,
            entry.result.disposition.takeUnless { it == CodeModeResultDisposition.REJECT }?.name,
            entry.result.origin.takeUnless { it == CodeModeResultOrigin.GENERIC }?.name,
            entry.result.payload,
        )
    }
}
