package splice.head.perf

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.core.perf.PerfTurnIds

/** The string facts a perf row carries beside the numeric snapshot. */
public data class PerfRowMeta(
    /** Null for a request splice refused at the gate before its body was read: no model was chosen, and the row
     *  then carries none rather than a stand-in. */
    val model: String?,
    val outcome: String,
    val compact: Boolean,
    /** The client's session tag (first 8 of x-claude-code-session-id), so an abort or a stall in
     *  the perf log is attributable to ONE Claude Code session in a single grep (2026-09-02: seven
     *  client aborts in two hours could only be tied to sessions by cross-reading transcripts). */
    val session: String? = null,
    val account: PerfAccount = PerfAccount(),
    val failure: PerfFailure = PerfFailure(),
    val transcript: PerfTranscriptIds = PerfTranscriptIds(),
) {
    /** Why the turn failed, beside its outcome tag: the cause, the retry loop's own attempt count (only when non-zero,
     *  so a row without retries is unchanged) and, V4-444, the upstream's own words where the upstream is what failed. */
    internal fun putFailureFacts(into: JsonObjectBuilder) {
        failure.cause?.let { into.put("cause", it) }
        if (failure.layers > 0) into.put("layers", failure.layers)
        failure.providerMessage?.let { into.put("provider_message", it) }
    }

    /** These optional string facts never enter the row's numeric snapshot. */
    internal fun putTranscriptFacts(into: JsonObjectBuilder) {
        transcript.sessionId?.let { into.put("session_id", it) }
        transcript.responseMessageId?.let { into.put("response_message_id", it) }
        transcript.messagesHash?.let { into.put("messages_hash", it) }
        transcript.turns.trace?.let { into.put("turn", it) }
        transcript.turns.request?.let { into.put("turn_id", it) }
        // V4-444: whether a capture was kept for this turn, said OUTRIGHT on every row. A null `turn` already
        // implied it, but a reader cannot tell "no capture was kept" from "the id is missing" out of an absence,
        // and the console has to choose between an open-the-request control and a reason there is none.
        into.put("capture", transcript.turns.trace != null)
    }
}

/** The login that carried a turn and whether its prompt cache started cold. [label] null means no account was
 *  proved for the turn, and then the row carries neither fact. */
public data class PerfAccount(
    val label: String? = null,
    val cacheCold: Boolean = false,
)

/** Why a turn failed and how hard the retry loop tried, beside the outcome tag on its perf row. */
public data class PerfFailure(
    /** V4-117: WHY this turn failed, as the taxonomy's cause, so a perf row can be grouped by cause
     *  rather than by the wire type the client happened to be told (the two differ by design — see
     *  WireType). Null for a turn that did not fail. */
    val cause: String? = null,
    /** V4-117: how many upstream attempts the retry loop made, as RECORDED by the loop itself.
     *  Written only when it is non-zero, so a row without retries looks exactly as it did before
     *  this field existed — the alternative would put layers=0 on every success in the file. */
    val layers: Int = 0,
    /** V4-444: what the PROVIDER said, in its own words, when the provider is the one that reported the failure.
     *  Null everywhere else, and splice's own sentence is never written here: the console shows this as the
     *  upstream speaking, so a gateway-authored line under that label would be a misattribution. The caller
     *  bounds it the way the client-facing failure text is bounded. */
    val providerMessage: String? = null,
)

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
    /** The hash of the request's messages, on a request that named a session: Requests links a cut turn's row to
     *  the re-send of the same messages in the same session. */
    val messagesHash: String? = null,
)
