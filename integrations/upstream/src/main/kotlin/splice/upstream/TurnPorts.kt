// NEW: the turn-path ROLES that cross a module boundary, named (HD-22, wave 4b).
//
// Wave 4a named the three highest-count SHAPES (log/env/clock, 126 sites) in :core. What was left
// is the long tail the census called "few roles, many shapes": 116 raw function types where the
// same question is asked from :upstream, all three dialects and :daemon-head, threaded by hand
// through every constructor between them. The ones HERE are the ones more than one module asks.
//
// WHY :upstream AND NOT :core. Their signatures name [WatchdogFired] and
// [splice.core.auth.Credentials]-derived request headers — turn vocabulary, not stdlib. :core is
// the framework-free bottom (module law) and these are the SPI's own contract; every consumer
// (the three dialects, :daemon-head) already depends on :upstream. Same reasoning that put HD-19's
// Waiter/Ticker here rather than in :core.
//
// WHY BY ROLE AND NEVER BY SHAPE — the sharpest case in the tree. [ClientGone] and
// [ClientFrameEmitted] are both `() -> Boolean`, are both about the same client, and are read
// within a few lines of each other on the retry path. They are OPPOSITE in effect: clientGone true
// means abandon the turn, clientFrameEmitted true means it is too late to retry it. Swapping them
// compiles today and produces the two worst outcomes this gateway has (a healthy turn abandoned,
// or duplicate output on the wire after a re-issue). Two types make that swap a compile error;
// one shared `BooleanSupplier` would have been strictly worse than the lambdas it replaced.
//
// The same census discipline applies to what is NOT here. `TurnDriver.watchdogFired: () -> Boolean`
// asks a DIFFERENT question from [WatchdogProbe] (`did it fire` vs `with what reason`), returns a
// different type, and stays named in :daemon-head. `ResponsesWsRunner.handshakeHeaders` is shape-
// adjacent to [CredentialHeaders] but non-suspending and one-shot at connect, and stays in its
// dialect.
//
// WHY `operator fun invoke`. Same as wave 4a: the role is the TYPE, not the call syntax, so every
// `clientGone()` / `onRetry(...)` call site in the tree is byte-identical and this wave's diff is
// exactly the declarations and the wiring.
package splice.upstream

import splice.core.auth.Credentials
import splice.core.failure.FailureCause
import splice.core.failure.Position
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldEnd
import splice.upstream.retry.RetryLedger
import splice.upstream.retry.WatchdogFired
import splice.upstream.transport.UpstreamResponse

/**
 * Whether the DOWNSTREAM client has gone away — flipped true when a write to it fails.
 *
 * The head owns this: it is the only party that holds the client socket. A provider or translator
 * must never hardcode it, because a constant `{ false }` makes `ClientAbandoned` unreachable dead
 * code and the turn keeps burning upstream quota for a client that hung up. Read on every frame by
 * all three stream translators ([splice.upstream.TurnSignals]) and by the turn driver.
 *
 * NOT [ClientFrameEmitted], which is also `() -> Boolean` and also about this client: that one
 * answers "has the client already SEEN output", and the two drive opposite decisions.
 */
public fun interface ClientGone {
    public operator fun invoke(): Boolean
}

/**
 * The watchdog's typed sentinel for this turn: null while healthy, the [WatchdogFired] reason once
 * a budget (no-first-byte, dead-air, hard cap) has been blown.
 *
 * The REASON, not a boolean, is the seam's whole content: it is what lets a translator end the
 * stream honestly with the specific cause the operator sees, which is L3's honesty rule — a
 * truncated turn must never be reported as a clean success.
 *
 * Named [WatchdogProbe] and not `WatchdogFired` because that name is taken, by the sealed class
 * this returns. The role is the asking; [WatchdogFired] is the answer.
 */
public fun interface WatchdogProbe {
    public operator fun invoke(): WatchdogFired?
}

/**
 * Whether the client has already been handed a frame — the G5 re-issue interlock.
 *
 * True means the upstream request MUST NOT be re-issued: the client has seen bytes, so a second
 * response would duplicate output on the wire. The default at every [UpstreamClient.post] overload
 * is `{ true }` — the safe answer, "assume it has" — and only the turn driver, which can prove
 * `FIRST_FRAME` is unmarked, wires a real probe.
 *
 * NOT [ClientGone]. See this file's header: they share `() -> Boolean` and nothing else.
 */
public fun interface ClientFrameEmitted {
    public operator fun invoke(): Boolean
}

/**
 * Retry diagnostics, for the log: each failed attempt, each decision, giving up, a malformed header omitted. It
 * decides nothing, and nothing it carries reaches a client: a person reads only lines written from a cause.
 */
public fun interface RetryNotice {
    public operator fun invoke(message: String)
}

/**
 * A caller's ONE-SHOT rewrite of the request body after a deterministic upstream rejection of its
 * CONTENT — RC-4's case is a 400 for stale encrypted-reasoning items.
 *
 * Returns the amended body, or null to leave the failure alone. Non-null swaps the body and retries
 * IMMEDIATELY, and fires at most once per post: this is a repair, not a retry policy, and a body
 * that is rejected twice is not one the caller can fix.
 */
public fun interface BodyAmendment {
    public operator fun invoke(status: Int, responseText: String, currentBodyJson: String): String?
}

/**
 * What the caller does with the upstream response once headers have arrived and the body is still
 * streaming.
 *
 * It runs INSIDE Ktor's `execute` block, which is the contract the shape hides: the response body
 * channel is alive only for the duration of this call, so anything that outlives it must be read
 * here, and cancelling the calling coroutine aborts the in-flight body (the lock-safe kill).
 */
public fun interface UpstreamHandler<T> {
    public suspend operator fun invoke(response: UpstreamResponse): StreamRead<T>
}

/**
 * The per-turn upstream headers derived from the CURRENT credentials — grok's `x-grok-conv-id`,
 * codex's session headers, a dialect's beta flags.
 *
 * Suspending and re-invoked per ATTEMPT, both deliberately: it is handed the credentials the
 * attempt will actually use, so a retry after a 401 refresh rebuilds its headers against the NEW
 * token instead of replaying the stale one. Per-turn and never provider-shared state — a shared
 * field races concurrent sessions into each other's affinity headers.
 */
public fun interface CredentialHeaders {
    public suspend operator fun invoke(credentials: Credentials): Map<String, String>
}

/**
 * Where a turn waits before it sends again (failure-handling slice 4, final-design.md section 8). The head's own
 * waits the step out on its clock and tells the person about a wait long enough to notice (TurnWaitsAtHead); a post
 * with no turn around it waits on its client's pacing and tells no one.
 */
public fun interface TurnWaits {
    /** Waits [afterMs] out, the wait of a step taken for [cause], met where the turn stood at [at]. */
    public suspend fun wait(afterMs: Long, cause: FailureCause, at: Position)

    /** Holds the turn as [ask] says until its cause lifts, its session's wall or its seven days come, or a wake
     *  (failure-handling slice 5). Waits with no turn around them hold nothing, and the turn ends as it did before. */
    public suspend fun hold(ask: HoldAsk): HoldEnd = HoldEnd.UNSUPPORTED

    /** Usable wait time left before the session's wall, with its response margin reserved, or the non-stream wait
     *  bound when sooner. Unbounded where nothing holds. */
    public fun holdWallLeftMs(): Long = Long.MAX_VALUE
}

/** One turn's retries: the [ledger] every post and round of the turn spends, and where its [waits] happen. */
public data class TurnRetry(public val ledger: RetryLedger, public val waits: TurnWaits)
