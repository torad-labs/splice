// NEW: the turn-path ROLES that cross a module boundary, named (HD-22, wave 4b).
//
// Wave 4a named the three highest-count SHAPES (log/env/clock, 126 sites) in :core. What was left
// is the long tail the census called "few roles, many shapes": 116 raw function types where the
// same question is asked from :provider-spi, all three dialects and :gateway, threaded by hand
// through every constructor between them. The ones HERE are the ones more than one module asks.
//
// WHY :provider-spi AND NOT :core. Their signatures name [WatchdogFired] and
// [splice.core.auth.Credentials]-derived request headers — turn vocabulary, not stdlib. :core is
// the framework-free bottom (module law) and these are the SPI's own contract; every consumer
// (the three dialects, :gateway) already depends on :provider-spi. Same reasoning that put HD-19's
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
// different type, and stays named in :gateway. `ResponsesWsRunner.handshakeHeaders` is shape-
// adjacent to [CredentialHeaders] but non-suspending and one-shot at connect, and stays in its
// dialect.
//
// WHY `operator fun invoke`. Same as wave 4a: the role is the TYPE, not the call syntax, so every
// `clientGone()` / `onRetry(...)` call site in the tree is byte-identical and this wave's diff is
// exactly the declarations and the wiring.
package splice.spi

import splice.core.auth.Credentials

/**
 * Whether the DOWNSTREAM client has gone away — flipped true when a write to it fails.
 *
 * The head owns this: it is the only party that holds the client socket. A provider or translator
 * must never hardcode it, because a constant `{ false }` makes `ClientAbandoned` unreachable dead
 * code and the turn keeps burning upstream quota for a client that hung up. Read on every frame by
 * all three stream translators ([splice.spi.TurnSignals]) and by the turn driver.
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
 * Reports that the upstream call is being retried, and why, in one operator-readable sentence.
 *
 * A NOTICE, not a decision: the retry has already been decided when this is called, and whatever
 * is wired here cannot veto it. Production wires the head log; the strings are the only account an
 * operator gets of a 429 cooldown, a deadline give-up or a stream re-issue, which is why the
 * failure text is built at the call site rather than by the sink.
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
    public suspend operator fun invoke(response: UpstreamResponse): T
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
