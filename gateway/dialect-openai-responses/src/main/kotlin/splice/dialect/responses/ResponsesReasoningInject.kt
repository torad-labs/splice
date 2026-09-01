// NEW: the single seam every reasoning input item passes through, split out of
// ResponsesRequestBuilder.kt (2026-08-17, concentration campaign). The inject-once-per-rs_-id seam
// is called from BOTH the redacted-thinking path (ResponsesInputBuilder.kt) and the tool-use cache
// reinjection path (ResponsesInputTools.kt) — a collaborator shared by two families belongs in
// neither. Every relocated member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import splice.core.util.JsonScalars
import splice.core.wire.RedactedThinkingBlock

internal class ResponsesReasoningInject {

    internal fun appendRedactedThinking(
        sink: JsonArrayBuilder,
        block: RedactedThinkingBlock,
        opts: BuildOptions,
    ) {
        if (!opts.compact && opts.replayReasoning.v) {
            opts.decodeReasoningEnvelope(block.data)?.let { addReasoningOnce(sink, it, opts) }
        }
    }

    /** The single seam every reasoning input item passes through: at-most-once per request by
     *  rs_ id, whether it arrived via the gateway cache or the legacy client round-trip. */
    internal fun addReasoningOnce(sink: JsonArrayBuilder, item: JsonObject, opts: BuildOptions) {
        // Dedup is an rs_-id concept; an id-less item (possible only through a custom decoder —
        // the Replay codec always carries one) has nothing to collide with and passes through.
        val id = JsonScalars.str(item["id"])
        if (id == null || opts.injectedReasoningIds.add(id)) sink.add(item)
    }
}
