// NEW: the ONE terminal-outcome precedence for every stream translator:
//   provider-reported failure > finished success > late watchdog fire > unfinished (client-gone / truncated).
// A FINISHED turn beats a late watchdog fire: the poller watches the whole coroutine, which can
// sit on the socket-EOF read AFTER the terminal frame already arrived — preferring the watchdog
// there discards a delivered turn and retries a successful generation, the exact quota waste the
// watchdog exists to prevent. All three dialects carried this chain in a different control
// structure, synced only by "parity" comments (and Passthrough drifted, discarding successful
// kimi turns); the ordering now lives here and translators supply only their states
// (review 2026-07-22).
package splice.spi

import splice.core.turn.TurnOutcome

/**
 * The outcome a dialect returns for a turn that reached its terminal frame — the success branch.
 *
 * Separate from [UnfinishedOutcome] despite the identical `() -> TurnOutcome` shape, and the split
 * is the L3 honesty rule in the type system: this branch is the ONLY one entitled to build a clean
 * success, and the one below is the branch that must not. They were adjacent parameters of the same
 * call with the same type, so transposing them was a two-token edit that compiled — and it would
 * have reported a truncated stream as a completed turn.
 */
public fun interface FinishedOutcome {
    public operator fun invoke(): TurnOutcome
}

/**
 * The outcome a dialect returns when the WATCHDOG ended the turn, given the reason it fired.
 *
 * The parameter is what separates it from its two siblings: the outcome must carry the specific
 * budget that blew (no-first-byte, dead-air, hard cap), because that string is the operator's only
 * account of why a turn stopped.
 */
public fun interface WatchdogOutcome {
    public operator fun invoke(fired: WatchdogFired): TurnOutcome
}

/**
 * The outcome a dialect returns for a turn that ended WITHOUT a terminal frame — client gone, or
 * the upstream stream simply stopped.
 *
 * The last branch of the precedence and the honest-failure one. See [FinishedOutcome] for why these
 * two are not one type.
 */
public fun interface UnfinishedOutcome {
    public operator fun invoke(): TurnOutcome
}

/** The dialect-reported turn states the precedence ranks (grouped: one cohesive argument). */
public data class TerminalStates(
    val providerFailure: TurnOutcome?,
    val finished: Boolean,
    val watchdogFired: WatchdogFired?,
) {
    /** The ONE ordering, unchanged: provider failure > finished > late watchdog > unfinished.
     *  A member of the state bundle it ranks (it was the first parameter) — the receiver is the
     *  only thing that moved. */
    public fun terminalPrecedence(
        onFinished: FinishedOutcome,
        onWatchdog: WatchdogOutcome,
        onUnfinished: UnfinishedOutcome,
    ): TurnOutcome = when {
        providerFailure != null -> providerFailure
        finished -> onFinished()
        watchdogFired != null -> onWatchdog(watchdogFired)
        else -> onUnfinished()
    }
}
