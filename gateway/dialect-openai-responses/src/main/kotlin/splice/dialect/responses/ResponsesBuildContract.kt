// NEW: the input and output DTOs of ResponsesRequestBuilder.build() — split out of
// ResponsesRequestBuilder.kt (2026-08-17, concentration campaign). These are the seam, not the
// builder: BuildOptions is read by six same-package files and BuiltRequest by nobody but build()
// itself and a test. Every member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.spi.ToolSearchController

public data class BuiltRequest(val req: JsonObject, val meta: TurnMeta, val toolSearch: ToolSearchController? = null)

public data class BuildOptions(
    public val compact: Boolean,
    public val originalModel: String,
    public val upstreamModel: String,
    public val configEffort: String?,
    public val configSummary: String?,
    public val showReasoning: ReasoningDisplay,
    /**
     * Inject prior redacted_thinking envelopes into the request input (multi-turn continuity).
     * Independent of [includeEncryptedReasoning]. Keep OFF for deepest fresh reasoning.
     */
    public val replayReasoning: InjectPriorReasoning,
    /**
     * Ask the server to return `reasoning.encrypted_content` on this turn's output.
     * Does NOT inject prior blobs into input. ON when reasoning is shown so we can store the
     * opaque handle for optional later replay (Grok Build / Codex always request this).
     */
    public val includeEncryptedReasoning: RequestEncryptedReasoning = RequestEncryptedReasoning(true),
    public val sessionId: String? = null,
    /** Decodes a redacted_thinking envelope back into a Responses reasoning input item. */
    public val decodeReasoningEnvelope: ReasoningEnvelopeDecoder,
    /** RC-3 (reasoning-cache 2026-07-24): the gateway-held cache lookup — tool_use id → the
     *  ordered envelopes of the turn that emitted it. Null = miss = today's behavior exactly.
     *  Wired by the provider; the default keeps unwired builds byte-identical. */
    public val reasoningLookup: ReasoningLookup = ReasoningLookup { null },
    /** The provider's tool-surface capability latch, read at build time. False = the backend
     *  rejected the shape on this daemon lifetime; build the full status-quo request. */
    public val toolSurfaceOpen: Boolean = true,
) {
    /** Per-REQUEST rs_-id dedup across BOTH injection paths (cache + legacy client replay):
     *  upstream 400s a duplicated reasoning id, and one turn's entry is shared by all its
     *  parallel tool_use blocks — first render injects, the rest skip (inject-once law).
     *  A BODY property, deliberately outside the primary constructor (review 2026-07-24): a
     *  constructor default would be ALIASED by copy() (defaults are not re-evaluated), silently
     *  sharing dedup state between two requests — here every instance, copies included,
     *  initializes its own fresh set, and equals/hashCode never see it. */
    public val injectedReasoningIds: MutableSet<String> = mutableSetOf()

    /** Per-REQUEST dedup for the declaration-replay injection (CHANGE 2, cache-prefix stability
     *  2026-07-25): a deferred tool's schema is declared AT MOST ONCE per request, immediately
     *  before the FIRST function_call in this turn's input that uses it. Same must-be-a-body-
     *  property law as [injectedReasoningIds] just above (a constructor default would be ALIASED
     *  by copy() — defaults are not re-evaluated — silently sharing dedup state between requests). */
    public val injectedToolDeclarationNames: MutableSet<String> = mutableSetOf()
}
