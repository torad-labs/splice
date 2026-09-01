// NEW: the control-plane ROLES the daemon injects into the control server, named (HD-22, wave 4b).
//
// Every one of these exists because :control must NOT depend on :app — the module law puts the
// control plane below the daemon that owns the heads, so each thing the server needs to know about
// the running daemon arrives as an injected question rather than a back-reference. Until now all
// five arrived as raw function types, declared twice each (ControlServer and ControlPayloads) with
// nothing but a shared parameter name to say they were the same question.
//
// WHY BY ROLE AND NEVER BY SHAPE, here: [FailedHeads] is `() -> Int`, as is
// [splice.spi.LiveLimit] one module over, and they are opposites — one REPORTS what already went
// wrong, the other BOUNDS what may happen next. [TopologyStale] and a liveness probe are both
// `() -> Boolean`. Naming the question is the only thing that keeps them apart.
package splice.control

/**
 * Renders the dashboard page served at `/` and `/dashboard`.
 *
 * Called PER REQUEST, not once at construction, which is the contract worth having a type for: the
 * daemon's implementation reads the page off the classpath with a filesystem override for local
 * development, so an operator editing the page sees the edit on reload rather than on restart.
 */
public fun interface DashboardPage {
    public operator fun invoke(): String
}

/**
 * Asks the daemon to begin an orderly shutdown — what `POST /mgmt/shutdown` actually does.
 *
 * A REQUEST and not the shutdown itself: production completes a signal the main coroutine is
 * waiting on, so the daemon tears itself down on its own thread, in its own order, while this
 * handler is still free to write the response. A default no-op means an embedded control server
 * (tests, the dashboard-only path) simply cannot be told to exit.
 */
public fun interface ShutdownDaemon {
    public operator fun invoke()
}

/**
 * Live count of heads that failed to ASSEMBLE or start — a gauge, read fresh per request.
 *
 * The `/health` readiness protocol needs it to converge on a degraded boot instead of waiting
 * forever for a head that will never become ready. Note the invariant it participates in:
 * `readyHeads + failedHeads == configuredHeads`, and it holds only against the CONFIGURED total,
 * because an assembly-failed head is counted here and is never in the `heads` map at all.
 */
public fun interface FailedHeads {
    public operator fun invoke(): Int
}

/**
 * Whether the topology on disk has diverged from the one this daemon booted — recomputed per
 * request, FAIL-OPEN (false when it cannot tell).
 *
 * Reporting only. Topology is deliberately not hot-reloadable, so this exists to make the required
 * restart VISIBLE to the shim, doctor and dashboard, never to trigger one.
 */
public fun interface TopologyStale {
    public operator fun invoke(): Boolean
}

/**
 * The head keys whose end-to-end turn path is currently STALLED, as measured by the turn-path
 * probe — empty meaning nothing is stalled.
 *
 * The keys and not a count, deliberately: `/health` names the wedged head. This is the signal the
 * 91-hour wedge went undetected without, when `ok` was a hardcoded true (2026-08-12), and it is a
 * different and stronger claim than [FailedHeads] — a head can be up, ready and counted healthy
 * while no turn can complete through it.
 */
public fun interface TurnPathStalled {
    public operator fun invoke(): List<String>
}

/**
 * The body of one authenticated `/mgmt` route, run only AFTER the bearer key matched.
 *
 * That ordering is the type's whole content: everything mutating on the control plane is wrapped in
 * one of these, and a route that responded outside one would be reachable without the management
 * key. It returns Unit because it has already written the response itself.
 */
public fun interface MgmtRoute {
    public suspend operator fun invoke()
}
