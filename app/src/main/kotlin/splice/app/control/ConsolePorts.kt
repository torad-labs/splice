// NEW: V4-161 — the nine ports ControlPlane wires into ControlServer after construction, moved
// verbatim out of ControlServer.kt (concentration, 2026-09-18). ControlServer.kt's header states
// the control plane this serves; the discipline these nine share is stated below.
package splice.app.control

import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.ConsoleAccounts
import splice.app.sources.mcp.McpAttached
import splice.client.mcp.McpInventory
import splice.client.mcp.McpSharing
import splice.configuration.add.AddConsole
import splice.core.client.ForegroundToolActivity
import splice.core.compaction.CompactionInstructions
import splice.core.config.KeyStore
import splice.core.config.StatePaths
import splice.core.topology.TopologyWriter
import splice.core.usage.QuotaReadFailureSource
import splice.diagnostics.doctor.DoctorFixes
import splice.diagnostics.doctor.DoctorReport
import splice.diagnostics.playground.PlaygroundProbe
import splice.head.turn.LiveTurnsByHead
import splice.head.wire.WireTaps
import splice.models.list.LocalRuntimes
import splice.models.roster.DeclaredHeads
import splice.sessions.activity.ActivityStores
import splice.sessions.http.SessionDriver
import splice.sessions.teams.TeamStore
import splice.usage.alerts.AlertStore
import splice.usage.budgets.BudgetEnforcement
import splice.usage.budgets.BudgetStore
import java.nio.file.Path

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
 *  A DEFAULT INSTANCE IS NOT AVAILABLE for any of these: the one time one was, a fresh event bus here meant the
 *  route streamed a daemon that looked quiet forever while every head reported to a different bus. */
public class ConsolePorts {
    /** Optional local tool callbacks. Unwired keeps the existing unknown or stale session policy. */
    public var foreground: ForegroundToolActivity? = null

    /** V4-136: how /api/compaction/instructions reaches the daemon's ONE compaction resolver.
     *
     *  A SETTABLE PROPERTY, not a constructor parameter — the constructor sits at the width
     *  ratchet's ceiling and V4-105 is burning it down, so the value arrives as an assignment
     *  ControlPlane makes right after construction.
     *
     *  UNSET IS NOT "NO INSTRUCTIONS CONFIGURED": the route answers a named 5xx, because an empty
     *  scope list from an unwired daemon would tell an operator that nothing is configured while
     *  the daemon compacts with rules — a confident false negative, which is the harm the route's
     *  400-not-404 rule exists to prevent. */
    public var compaction: CompactionInstructions? = null

    /** V4-130: the console's activity stores (message edges, activity labels), assigned by ControlPlane
     *  after construction like [compaction]. Null answers the kept-store and team-day routes with a named 503. */
    public var activity: ActivityStores? = null

    /** V4-131: the daemon's team store, assigned by ControlPlane after construction like [activity].
     *  Null answers every team route with a named 503 and leaves the sessions rows' `team` null. */
    public var teams: TeamStore? = null

    /** Oct 10, 2026: the terminal sessions are started in, with the record of which pane carries which session
     *  (SessionTerminal, TMUX.md). Assigned by ControlPlane after construction like [teams]; null answers a
     *  member's start and stop with a named 503, because splice cannot drive a session it cannot open. */
    public var sessionDriver: SessionDriver? = null

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

    /** V4-220 item 4: the fixes POST /api/doctor/fix/{id} runs. Null answers a named 503, never a
     *  fix that silently did nothing. */
    public var doctorFixes: DoctorFixes? = null

    /** The upgrade status, its check and run, and whether systemd owns this daemon: see [LifecyclePorts]. */
    internal val lifecycle: LifecyclePorts = LifecyclePorts()

    /** V4-128: the writer over splice.toml, assigned by ControlPlane after construction like [teams] and
     *  read at call time. Null answers GET and PUT /api/topology with a named 503. */
    public var topology: TopologyWriter? = null

    /** V4-132: the login/remove/relabel machinery behind POST/GET /api/auth/{head}/login[/{id}] and
     *  DELETE/PATCH /api/auth/{head}/accounts/{label} — see [ConsoleAccounts]'s own KDoc for why it
     *  is a port at all. Assigned by ConsoleWiring after construction like every port above; null
     *  answers those four routes with a named 503, never a payload that reads as "no accounts". */
    public var accounts: ConsoleAccounts? = null

    /** Native command-local logins, assigned before management and launch requests are served. */
    public var claudeLogins: ClaudeLoginPlaces? = null

    /** Each login's last failed limits read, assigned by ControlPlane; null reads as no failure on any login. */
    public var quotaReadFailures: QuotaReadFailureSource? = null

    /** V4-133 (FEATURES.md §5/§6): the daemon's ONE budget store, assigned by ControlPlane after
     *  construction like [teams]. Null answers GET/PUT /api/budgets with a named 503 — an
     *  unwired store must never read as "nothing budgeted". */
    public var budgets: BudgetStore? = null

    /** The same per-head ledgers turn admission and accounting use, never per-account spend. */
    public var budgetSpending: BudgetEnforcement? = null

    /** The census of every MCP server Claude Code loads, the one the host reports, assigned by ControlPlane. GET
     *  /api/mcp/memory reads it to name the copies it measures; null measures no server. */
    public var mcpInventory: McpInventory? = null

    /** The host's sharing plan, assigned with [mcpInventory]: it classes every server the measurement names. */
    public var mcpSharing: McpSharing? = null

    /** The sessions the hub's front has bound to each server, assigned with the hub. GET /api/mcp/memory names them
     *  beside the sessions whose own copies it measures; null names only those, as on a daemon with the hub off. */
    public var mcpAttached: McpAttached? = null

    /** V4-133: the daemon's ONE alert-settings store, assigned like [budgets]. Null answers
     *  GET/PUT /api/alerts and POST /api/alerts/test with a named 503. */
    public var alerts: AlertStore? = null

    /** V4-133: the daemon's ONE upstream probe for POST /api/playground, assigned like [budgets].
     *  Null answers with a named 503 — never a payload that reads as a completed run. */
    public var playground: PlaygroundProbe? = null

    /** V4-220 item 3: the key store GET/PUT/DELETE /api/keys read and write — the same keys.toml the
     *  api-key heads read on every request, assigned by ConsoleWiring like [budgets]. Null answers the
     *  three routes with a named 503, never an empty key list. */
    public var keys: KeyStore? = null

    /** V4-220 item 3: `splice add` as the console runs it, behind /api/add. Assigned by ConsoleWiring;
     *  null answers every add route with a named 503, never an empty profile list. */
    public var add: AddConsole? = null

    /** §9i: each local runtime's own answers, behind GET /api/models/local. Null answers a named 503. */
    public var localRuntimes: LocalRuntimes? = null

    /** V4-239: the trace directory every head's TraceStore writes, behind GET /api/heads/{head}/trace.
     *  Null answers a named 503, never "no turns traced". */
    public var traceDir: Path? = null

    /** V4-381: the state paths this daemon's perf writers actually use. Null refuses the kept-turns
     *  inventory, rather than telling the console no turn statistics exist on an unwired daemon. */
    public var turnStatistics: StatePaths? = null

    /** Where the wrapper links live, for a removal to unlink; null reads the install's own bin dir. */
    public var wrapperBin: Path? = null

    /** V4-239: the daemon's ONE wire-tap registry, the one HeadServerFactory registers each head's tap
     *  in, behind GET /api/heads/{head}/wire. Null answers a named 503, never "the tap is off". */
    public var wires: WireTaps? = null

    /** V4-319: the daemon's ONE live-turn registry, the one HeadServerFactory registers each head's turns
     *  in, behind GET /api/heads/{head}/turns/live and the stop. Null answers a named 503. */
    public var liveTurns: LiveTurnsByHead? = null
}
