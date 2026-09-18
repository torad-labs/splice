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

import splice.core.topology.HeadModel

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

/**
 * V4-127: the `doctor --json` report, as the CLI renders it (JSON TEXT, not a JsonObject, so these
 * ports stay free of a serialization import and the daemon decides the shape in the module that
 * already owns it).
 *
 * [UpgradeStatus] below returns the same `() -> String`, and [MgmtRoute] is a `() -> Unit` shape one
 * line up — three questions that a raw function type could not tell apart. Naming the question is
 * what keeps a caller from wiring the upgrade payload into the doctor route and having both compile.
 */
public fun interface DoctorReport {
    public operator fun invoke(): String
}

/**
 * V4-127: what the upgrade surface knows — installed, latest, whether a rollback is available —
 * rendered as JSON text for the console's upgrade bay.
 *
 * A separate role from [DoctorReport] despite the identical shape: one is a DIAGNOSIS of the running
 * daemon, the other is a VERSION question about the artifact on disk, and they are answered by
 * different code with different failure modes.
 */
public fun interface UpgradeStatus {
    public operator fun invoke(): String
}

/**
 * What the topology declares about ONE head: the provider key it is registered under, and the model
 * list it declares (`HeadConfig.models`, each an id and an optional slot).
 *
 * [models] IS NULLABLE BECAUSE `HeadConfig.models` IS. A head whose operator declared no tiers is a
 * real state, and it is a different fact from a head the wiring never named — which is an ABSENT KEY
 * in the map [DeclaredHeads] returns, never a null value here. The two are kept apart on purpose;
 * see the role's own KDoc for what conflating them would draw on the page.
 */
public data class DeclaredHead(
    /** The registry's provider key for this head. The models page GROUPS by it — FEATURES.md §6,
     *  decided 2026-09-18 for V4-127 — so it is part of the payload, not a convenience. */
    val provider: String,
    val models: List<HeadModel>?,
)

/**
 * V4-127: what the topology declared about every head — the provider key and the declared model list
 * — for the whole daemon at once.
 *
 * WHY THIS IS A ROLE AND NOT FIELDS ON [ManagedHead], where a per-head static fact would naturally
 * live: that record is already a recorded constructor-width offender at 17 parameters, and carrying
 * these two facts pushed it to 19 across a FOURTH subsystem. The ratchet read the growth as WIDENED
 * and refused — "a recorded offender is DEBT, not permission to keep adding parameters" — and
 * re-baselining it would be the bypass that gate exists to prevent. So the facts arrive here and are
 * assigned on ControlServer after construction, the shape `compaction` took in V4-136.
 *
 * THE ROUTE GENUINELY NEEDS THEM, which is why this is a move rather than a deletion: the payload
 * reports the join in BOTH directions, and only the declared list knows that a slot WAS declared. A
 * resolved model that no tier names carries `slot: null`; a declared slot that resolved to nothing
 * must still be its own row, or the page silently omits the tier the operator is missing.
 *
 * AN ABSENT KEY IS NOT A NULL VALUE. `HeadConfig.models` is itself nullable, so a present key whose
 * [DeclaredHead.models] is null is a real state — this head's operator declared no tiers — while an
 * absent key is a head the wiring did not name. The route answers 5xx for the second and renders the
 * first. A per-head `List<HeadModel>?` lookup could not tell them apart, which is why the role answers
 * a MAP: reporting a wiring gap as an empty declaration would draw every served model with no tier
 * naming it, a confident false negative about the one thing this page exists to display. */
public fun interface DeclaredHeads {
    /** Every head the topology declares, by head key. An absent key means the wiring did not name
     *  that head — the route's named 5xx — never that the head declares nothing. */
    public operator fun invoke(): Map<String, DeclaredHead>
}
