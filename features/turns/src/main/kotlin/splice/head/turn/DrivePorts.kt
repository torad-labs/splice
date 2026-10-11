// NEW: the turn-drive ROLES of the head, named (HD-22, wave 4b).
//
// TurnDriver's round runners are built out of injected decisions — post a round, finish the turn,
// is the client still there, did the watchdog trip — and every one of them arrived as a raw
// function type. HD-24 moved the six round-runner ports (PostRoundToSink, PostRound, FinishTurn,
// WatchdogTripped, RoundFailureHook, SearchRoundCounter) and RunnerSignals into
// splice.head.round.RoundPorts.kt, beside the runners that are their only consumers now. What
// stays here are the two ports whose consumers (WsRoundDriver, RequestMaterializationGate) stayed
// in head.
package splice.head.turn

import splice.core.turn.TurnOutcome

/**
 * Classifies a round that produced ZERO upstream events into an outcome.
 *
 * Given the drive, the outcome so far, the captured body text and the event baseline: a zero-event
 * round is exactly the case where the upstream sent something that is not SSE at all — an HTML or
 * JSON login page from a dead head — so the classification depends on the captured raw body and not
 * on the event stream that never existed.
 */
internal fun interface ZeroEventClassifier {
    operator fun invoke(drive: TurnDrive, outcome: TurnOutcome, bodyText: String, eventsBase: Long): TurnOutcome
}

/**
 * The request decode-and-translate work run while a materialization lease is held.
 *
 * The lease bounds the short, allocation-heavy phase that simultaneously holds the raw UTF-8 body,
 * the Anthropic tree and the translated tree — so what belongs inside one of these is exactly that
 * phase, and the long-lived upstream turn (bounded by the separate turn gate) does not. Something
 * that held its lease across the upstream call would starve every other head's materialization.
 *
 * `T : Any` at the fast-fail entry point is not incidental: null there means CONTENTION and nothing
 * else, so a legitimately-null result is made unrepresentable rather than ambiguous. The body cap is
 * the second thing a materialization can answer, and it answers it as a [Materialized] case for the
 * same reason: a refusal the caller must render is a branch the compiler checks, not a thrown name.
 */
internal fun interface MaterializedRequest<T> {
    suspend operator fun invoke(): T
}

/**
 * What a materialization answered: the decoded request, or the body cap it refused.
 *
 * [TooLarge] exists because the cap is read in two places that cannot share a return — the declared
 * Content-Length, checked before any admission wait, and the running total inside the body read, a
 * frame at a time. Both are refusals of the SAME request with the SAME 413 and the same bytes on the
 * wire, so [AdmissionGate.materializeOrRespond] renders them in one `when` branch. Before V4-440 the
 * read threw a RequestBodyTooLarge that only that one catch recovered: every other caller of the
 * reader inherited an exception it had no reason to know about, and nothing checked that it did.
 */
internal sealed class Materialized<out T : Any> {
    data class Done<T : Any>(val value: T) : Materialized<T>()

    /** [limit] is the cap that refused the body, which the 413's text names. */
    data class TooLarge(val limit: Int) : Materialized<Nothing>()
}
