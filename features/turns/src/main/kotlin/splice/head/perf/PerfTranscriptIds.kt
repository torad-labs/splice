package splice.head.perf

import splice.core.perf.PerfTurnIds

/** The ids that join a perf row to the rest of the record: the trace turn and request turn, the full client
 *  session, the client-facing response message (V4-354), and the conversation key the in-memory preflight
 *  measurements use. Each is optional: a head with no trace, or a turn that never reached a client, has none. */
public data class PerfTranscriptIds(
    /** V4-345: the id of the trace turn that recorded this turn's request and answer, on a head that
     *  keeps a trace, so the console opens the request a person clicked by its id rather than guessing
     *  it by time. Null on a head that keeps none, and then the row carries no `turn`. */
    val turns: PerfTurnIds = PerfTurnIds(),
    /** The full client session id, for joining the local transcript without guessing by timestamp or
     *  the shortened session tag. */
    val sessionId: String? = null,
    /** Splice's client-facing response message id, for the same join. */
    val responseMessageId: String? = null,
    /** Same stable first-prompt key the provider uses, only for in-memory preflight measurements. */
    val conversationKey: String? = null,
)
