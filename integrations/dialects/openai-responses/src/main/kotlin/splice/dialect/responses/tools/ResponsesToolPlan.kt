// NEW: "what this request does about tools" — wraps ToolPartitioner, decides the declaration-replay
// map, and mints the search controller. Split out of ResponsesRequestBuilder.kt (2026-08-17,
// concentration campaign) into its own sibling rather than into ToolSurface.kt or
// ResponsesToolSearch.kt (both themselves campaign targets already at/over the gate) — pushing work
// into them would move concentration sideways again. Every relocated member kept its identical name
// and argument list.
package splice.dialect.responses.tools

import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.request.BuildOptions
import splice.upstream.ToolNameShortener
import splice.upstream.ToolSearchPolicy

internal class ResponsesToolPlan(
    private val quirks: ResponsesQuirks,
    private val toolNames: ToolNameShortener = ToolNameShortener(),
) {

    private val partitioner = ToolPartitioner(quirks)

    /** Partition FIRST, before the message walk: it is a pure function of (body.tools, policy)
     *  ONLY (ToolSurface.kt header) — the ORIGINAL call site inside build(). */
    internal fun partition(body: AnthropicRequest, opts: BuildOptions): ToolPartition? =
        if (body.tools.isNotEmpty()) partitioner.partitionTools(body, opts) else null

    // ── tools ────────────────────────────────────────────────────────────────

    /** Only client mode needs a gateway search controller. Hosted searches finish inside Meta;
     *  a bare partition (off, below the floor) still returns no controller. */
    internal fun toolSearchControllerFor(partition: ToolPartition?, opts: BuildOptions): ToolSearchPolicy? {
        val policy = quirks.tools.toolSurface
        if (policy == null || policy.mode == ToolSearchMode.HOSTED) return null
        if (partition == null || !partition.deferring) return null
        return ResponsesToolSearchPolicy(
            index = ToolSearchIndex(partition.deferred),
            policy = policy,
            emitStrict = quirks.tools.emitStrict,
            forceStrictFalse = quirks.tools.forceStrictFalse,
            normalizeSchemas = quirks.tools.normalizeToolSchemas,
            decodeReasoningEnvelope = opts.handoff.decode,
            names = toolNames,
        )
    }

    /** The declaration-replay input for CHANGE 2 (cache-prefix stability, 2026-07-25): every
     *  DEFERRED tool this turn's transcript already named via a ToolUseBlock, keyed by name so
     *  [ResponsesInputTools] can hand its full schema straight to the injector without a second
     *  body.tools lookup. [ToolPartitioner.warmToolNames] used to gate the (now-removed)
     *  always-eager promotion in ToolSurface.kt; it feeds this instead — see that file's header. A
     *  tool warm but NOT in this map (eager, or dropped from body.tools since it was last used)
     *  gets no declaration on purpose: eager tools are already declared via tools[] every turn, and
     *  a dropped tool has no schema to declare — the degrade-to-status-quo path [appendToolUse]
     *  documents. */
    internal fun declarationCandidates(body: AnthropicRequest, partition: ToolPartition?): Map<String, ToolDefinition> {
        if (quirks.tools.toolSurface?.mode == ToolSearchMode.HOSTED) return emptyMap()
        val deferred = partition?.deferred
        if (deferred.isNullOrEmpty()) return emptyMap()
        val warm = partitioner.warmToolNames(body)
        return deferred.filter { it.name in warm }.associateBy { it.name }
    }
}
