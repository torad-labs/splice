// NEW: accepted results and their rendered follow-ups share one ordered mutable owner.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeAcceptedResult
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeResultSnapshot
import splice.provider.codex.state.heap.CodeModeAcceptedWeight
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import java.util.Collections

/** Acceptance order is durable sequence order. A result and its media never disagree on keys. */
internal class CodeModeAccepted(entries: Map<String, CodeModeAcceptedResult> = emptyMap()) {
    private val entries: MutableMap<String, CodeModeAcceptedResult> = LinkedHashMap(entries)
    private val weight = CodeModeAcceptedWeight(this.entries)

    val results: Map<String, CodeModeResult> get() = entries.mapValues { (_, entry) -> entry.result }

    /** The accepted ids in acceptance order, as a read-only view: read it now, never keep it. */
    fun ids(): Set<String> = Collections.unmodifiableSet(entries.keys)

    fun holds(id: String): Boolean = id in entries

    /** Transport receipts stay immutable; ordinary generic replacements retain their existing acceptance policy. */
    fun changes(supplied: Map<String, CodeModeResult>): Map<String, CodeModeResult> = supplied.filter { (id, result) ->
        val prior = entries[id]?.result
        prior?.origin != CodeModeResultOrigin.MCP && (prior == null || result.origin != CodeModeResultOrigin.MCP)
    }

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
    ): Long = weight.growth(supplied, media, clientOutputDigests)

    /** What [snapshot] still holds of the prior results that accepting [supplied] replaces. */
    fun kept(
        snapshot: CodeModeRecordSnapshot,
        supplied: Map<String, CodeModeResult>,
    ): Long = weight.kept(snapshot, supplied)

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
