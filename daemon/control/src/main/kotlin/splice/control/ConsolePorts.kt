// NEW: V4-161 — the nine ports ControlPlane wires into ControlServer after construction, moved
// verbatim out of ControlServer.kt (concentration, 2026-09-18). ControlServer.kt's header states
// the control plane this serves; the discipline these nine share is stated below.
package splice.control

import splice.accounts.signin.ConsoleAccounts
import splice.control.api.EventBus
import splice.control.api.fleet.DaemonSupervised
import splice.control.api.turns.PlaygroundProbe
import splice.core.compaction.CompactionInstructions
import splice.core.topology.TopologyWriter
import splice.sessions.activity.ActivityStores
import splice.sessions.teams.TeamStore
import splice.usage.alerts.AlertStore
import splice.usage.budgets.BudgetStore

/** The console's injected ports: everything the daemon wires into [ControlServer] AFTER it is
 *  constructed, held in one place because they are one idea repeated nine times.
 *
 *  V4-161 (2026-09-18) moved them out of ControlServer, and the reason is written in their own
 *  comments. Every one of them says the same three things: it is a settable property rather than a
 *  constructor parameter because the constructor sits at the width ratchet's ceiling; it is read at
 *  CALL time through a lambda, never captured, because ControlPlane assigns it after construction
 *  and a captured null stays null forever; and NULL MEANS UNWIRED, answered with a NAMED 5xx rather
 *  than an empty payload, because an empty roster, a clean doctor report, nothing to upgrade or no
 *  restart coming are each a did-not-run wearing a legitimate answer. Nine properties sharing one
 *  discipline are a type, and ControlServer already said so in prose: "This is the one discipline
 *  all four share, and it is why they are declared together."
 *
 *  IT ALSO PAID FOR ITSELF ON THE CONCENTRATION BAND, which is what made it urgent rather than
 *  tidy. These nine declarations were the ONLY reason ControlServer named ActivityStores,
 *  CompactionInstructions, TeamStore and TopologyWriter: it never used those types for anything but
 *  handing them to an ActivitySource, a TeamSource or a TopologyWriterSource. Four subsystems entered a
 *  file that had no use for them, at 8 C each on a file 29 C over the band.
 *
 *  A DEFAULT INSTANCE IS NOT AVAILABLE for any of these, and the EventBus comment below records
 *  what happened the one time one was: a fresh bus here meant the route streamed a daemon that
 *  looked quiet forever while every head reported to a different bus. */
public class ConsolePorts {
    /** v0.4.0 (V4-126, FEATURES.md §6): the console event bus GET /api/events streams from.
     *
     *  A SETTABLE PROPERTY, not a constructor parameter: as a parameter it widened this constructor
     *  17 -> 18, which the constructor-width ratchet reports as WIDENED on a file already recorded as
     *  debt. ControlPlane assigns it right after construction, the same shape [compaction] takes.
     *
     *  NO DEFAULT INSTANCE (V4-134). V4-126 gave this a fresh `EventBus()` so ControlPlane compiled
     *  unchanged, and that default is exactly how a route ends up streaming a bus nobody publishes
     *  to: every head reports to the daemon's ONE bus (ControlPlane's ConsoleEventPublisher), so a
     *  private one here would stream a quiet daemon forever while the heads talked to nobody. Unset,
     *  the route answers a NAMED 503 instead — the discipline every console port here keeps. */
    public var events: EventBus? = null

    /** V4-136: how /api/compaction/instructions reaches the daemon's ONE compaction resolver.
     *
     *  A SETTABLE PROPERTY, not a constructor parameter — the constructor sits at the width
     *  ratchet's ceiling and V4-105 is burning it down, so the value arrives as an assignment
     *  ControlPlane makes right after construction (the same shape [events] took).
     *
     *  UNSET IS NOT "NO INSTRUCTIONS CONFIGURED": the route answers a named 5xx, because an empty
     *  scope list from an unwired daemon would tell an operator that nothing is configured while
     *  the daemon compacts with rules — a confident false negative, which is the harm the route's
     *  400-not-404 rule exists to prevent. */
    public var compaction: CompactionInstructions? = null

    /** V4-130: the console's activity stores (message edges, activity labels), assigned by ControlPlane
     *  after construction like [events]. Null answers the two edges routes with a named 503. */
    public var activity: ActivityStores? = null

    /** V4-131: the daemon's team store, assigned by ControlPlane after construction like [activity].
     *  Null answers every team route with a named 503 and leaves the sessions rows' `team` null. */
    public var teams: TeamStore? = null

    /** V4-127: the console's four injected ports, each a SETTABLE PROPERTY for the same reason
     *  [compaction] is — the constructor sits at the width ratchet's ceiling and V4-105 is burning it
     *  down, so a new route input arrives as an assignment ControlPlane makes after construction.
     *
     *  EVERY ONE OF THEM IS READ AT CALL TIME through a lambda ([modelsRoute], [doctorRoute],
     *  [upgradeRoute], [daemonRoutes] below), never captured: a route that captured the value at
     *  construction would capture null forever and answer its unwired 5xx against a daemon that had
     *  wired it a moment later. That is the same trap [compaction] was written to avoid.
     *
     *  NULL MEANS UNWIRED, and every route below answers a NAMED 5xx for it rather than an empty
     *  payload. This is the one discipline all four share, and it is why they are declared together:
     *  an absent declared-model roster, doctor report, upgrade status or restart control would each
     *  render as a confident negative — no tiers declared, nothing wrong, nothing to upgrade, no
     *  restart coming — every one of them a did-not-run wearing a legitimate answer. */
    public var declaredHeads: DeclaredHeads? = null
    public var doctor: DoctorReport? = null
    public var upgrade: UpgradeStatus? = null

    /** V4-137: whether anything would bring this daemon back after it drains. Assigned beside the
     *  ports above, and read at CALL time by the routing lambda for the same reason they are.
     *
     *  NULL IS NOT "ASSUME SUPERVISED". The restart route REFUSES when this is unwired, because the
     *  two possible defaults are both wrong in the same direction: assuming supervised turns the
     *  console's restart button into a stop button on an unsupervised daemon, and assuming the
     *  opposite would refuse a restart on the host that can actually perform one. */
    public var supervised: DaemonSupervised? = null

    /** V4-128: the writer over splice.toml, assigned by ControlPlane after construction like [teams] and
     *  read at call time. Null answers GET and PUT /api/topology with a named 503. */
    public var topology: TopologyWriter? = null

    /** V4-132: the login/remove/relabel machinery behind POST/GET /api/auth/{head}/login[/{id}] and
     *  DELETE/PATCH /api/auth/{head}/accounts/{label} — see [ConsoleAccounts]'s own KDoc for why it
     *  is a port at all. Assigned by ConsoleWiring after construction like every port above; null
     *  answers those four routes with a named 503, never a payload that reads as "no accounts". */
    public var accounts: ConsoleAccounts? = null

    /** V4-133 (FEATURES.md §5/§6): the daemon's ONE budget store, assigned by ControlPlane after
     *  construction like [teams]. Null answers GET/PUT /api/budgets with a named 503 — an
     *  unwired store must never read as "nothing budgeted". */
    public var budgets: BudgetStore? = null

    /** V4-133: the daemon's ONE alert-settings store, assigned like [budgets]. Null answers
     *  GET/PUT /api/alerts and POST /api/alerts/test with a named 503. */
    public var alerts: AlertStore? = null

    /** V4-133: the daemon's ONE upstream probe for POST /api/playground, assigned like [budgets].
     *  Null answers with a named 503 — never a payload that reads as a completed run. */
    public var playground: PlaygroundProbe? = null
}
