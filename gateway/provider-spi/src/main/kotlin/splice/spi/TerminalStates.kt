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

/** The dialect-reported turn states the precedence ranks (grouped: one cohesive argument). */
public data class TerminalStates(
    val providerFailure: TurnOutcome?,
    val finished: Boolean,
    val watchdogFired: WatchdogFired?,
)

public fun terminalPrecedence(
    states: TerminalStates,
    onFinished: () -> TurnOutcome,
    onWatchdog: (WatchdogFired) -> TurnOutcome,
    onUnfinished: () -> TurnOutcome,
): TurnOutcome = when {
    states.providerFailure != null -> states.providerFailure
    states.finished -> onFinished()
    states.watchdogFired != null -> onWatchdog(states.watchdogFired)
    else -> onUnfinished()
}
