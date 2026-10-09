package splice.core.turn

/** What a round hands on to the gateway's controllers, which forward it without reading it. */
public data class RoundHandoffs(
    /** splice-reasoning envelopes (base64) of THIS round's encrypted reasoning items, for
     *  reasoning-continuation replay. Populated only when the turn is fold-eligible; empty
     *  otherwise (opaque handles — the gateway forwards them to the provider's fold controller,
     *  never reads them). */
    val reasoningEnvelopes: List<String> = emptyList(),
    /** tool_search_call items THIS round emitted. Non-empty only on a responses turn with
     *  deferral active; the gateway never reads their contents — it hands them to the turn's
     *  ToolSearchPolicy (the same opaque-forwarding rule as reasoningEnvelopes). */
    val toolSearches: List<ToolSearchCall> = emptyList(),
    /** Gateway-local custom calls. Empty keeps every non-bridge outcome byte-identical. */
    val customCalls: List<GatewayCustomCall> = emptyList(),
)
