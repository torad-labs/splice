// NEW: the reasoning-envelope ROLES of the Responses dialect, named (HD-22, wave 4b).
//
// The envelope codec is splice-reasoning v1: an opaque `redacted_thinking` handle Claude Code can
// store and hand back, wrapping the encrypted reasoning a Responses turn produced. Four seams in
// this dialect speak about it, and until now all four were raw function types threaded through
// constructors — `decodeReasoningEnvelope` alone appeared in ResponsesFold, ResponsesToolSearch,
// ResponsesReanchorController and ResponsesRequestBuilder, four separate declarations of the same
// contract with nothing tying them together but the parameter name.
//
// WHY FOUR TYPES AND NOT ONE CODEC OBJECT. The consumers are genuinely asymmetric and grouping
// them would hand each one capabilities it must not have: the fold and the re-anchor controller
// only ever DECODE, the stream translator only ever ENCODES, and the request builder decodes but
// must never encode. A single codec parameter would make "the builder encoded something" a
// review-time question instead of a compile-time impossibility.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject

/**
 * Decodes a `redacted_thinking` envelope back into a Responses reasoning INPUT item.
 *
 * Null means "not one of ours, or not decodable" and is the ordinary path, not an error: a client
 * may hand back a handle this daemon never minted, a handle from a previous envelope version, or a
 * genuine `redacted_thinking` block from a different provider entirely. Every caller treats null as
 * "inject nothing", which is exactly the pre-reasoning-replay behaviour.
 */
public fun interface ReasoningEnvelopeDecoder {
    public operator fun invoke(envelope: String): JsonObject?
}

/**
 * Encodes one reasoning item into a `redacted_thinking` envelope for the wire.
 *
 * The inverse of [ReasoningEnvelopeDecoder] and deliberately a SEPARATE type: the two are wired at
 * different seams, by different components, and only the stream translator is entitled to mint an
 * envelope. Null means this item produced no envelope and no `redacted_thinking` block is emitted.
 */
public fun interface ReasoningEnvelopeEncoder {
    public operator fun invoke(item: JsonObject): String?
}

/**
 * RC-3: the gateway-held cache lookup — a `tool_use` id to the ordered envelopes of the turn that
 * emitted it.
 *
 * Null is a MISS and means today's behaviour exactly, which is why the default is `{ null }`: an
 * unwired build is byte-identical. The list is ORDERED because position is the point — the point of
 * the cache is to reinject the model's plan in-position for the next tool-result request.
 */
public fun interface ReasoningLookup {
    public operator fun invoke(toolUseId: String): List<String>?
}

/**
 * RC-1: called ONCE at a successful tool-use terminal with the round's real upstream `function_call`
 * ids and its ordered reasoning envelopes — the write side of what [ReasoningLookup] reads.
 *
 * "Real" is load-bearing and is why this is not a general event hook: synthetic tool ids
 * (`toolu_synth_*`) repeat across turns, so keying the cache with one bleeds one turn's reasoning
 * into another. The default no-op keeps the reducer byte-identical when the provider wires no cache.
 */
public fun interface TurnReasoningSink {
    public operator fun invoke(toolIds: List<String>, envelopes: List<String>)
}
