// NEW: three same-typed booleans about encrypted reasoning sat at adjacent constructor positions
// (BuildOptions, StreamTurnContext) — the exact adjacency that produced this session's
// replayReasoning / emitEncryptedReasoning collision. Wrapping each makes passing one where another
// is expected a COMPILE error (#924 Phase 2, tier-1). They mean genuinely different things:
package splice.dialect.responses.reasoning

/** INJECT prior redacted_thinking envelopes into the request input (multi-turn continuity). */
@JvmInline
internal value class InjectPriorReasoning(public val v: Boolean)

/** ASK the server to RETURN reasoning.encrypted_content on this turn's output (the opaque handle). */
@JvmInline
internal value class RequestEncryptedReasoning(public val v: Boolean)

/** EMIT redacted_thinking wire blocks to the client on the stream side (Claude Code stores them). */
@JvmInline
internal value class EmitEncryptedReasoning(public val v: Boolean)
