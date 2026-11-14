// NEW: the console's stores and its one publisher, moved out of ControlPlane.kt by their own seam, behavior unchanged
// (concentration, Oct 13, splice-lead). Built in the order ControlPlane built them; ControlPlane hands each one on by
// reference, so the heads, the routes and the publisher still share the same instance of each.
package splice.app

import splice.app.control.ControlServer
import splice.app.daemon.BootedTopology
import splice.app.head.SESSION_FALLBACKS_FILE
import splice.app.head.SessionFallbackStore
import splice.app.probe.PlaygroundProviders
import splice.app.probe.UpstreamPlaygroundProbe
import splice.app.sources.PerfSourceFiles
import splice.core.perf.KeptHistory
import splice.sessions.prompt.SlotInstructions
import splice.sessions.registry.ForegroundTools
import splice.upstream.LifecycleScope
import splice.usage.alerts.AlertCommand
import splice.usage.alerts.AlertDelivery

internal class ControlStores(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
    private val probeScope: LifecycleScope,
) {
    /** V4-131: the daemon's ONE team store: the routes edit it and every head's slot resolver reads it. */
    val teams = ConsoleWiring.teamStore(daemon.statePaths, daemon.log)

    /** V4-133 (FEATURES.md §5/§6): the daemon's ONE budget and alert-settings stores. */
    val budgets = ConsoleWiring.budgetStore(daemon.statePaths)
    val alerts = ConsoleWiring.alertStore(daemon.statePaths)

    // The window a person set on Settings > Your data, read at every use so shortening it needs no
    // restart: this one reader is what every head's perf, archive and economics store asks.
    val perfRows = PerfSourceFiles(daemon.statePaths, KeptHistory { daemon.config.getConfig().historyWindow })
    val foregroundTools = ForegroundTools()

    /** Nov 13, 2026: the model each session named to fall back to while the one it asks for is held on every login: the
     *  Sessions routes set it, and every head's turns read it. */
    val sessionFallbacks = SessionFallbackStore(daemon.statePaths.stateDir.resolve(SESSION_FALLBACKS_FILE))

    /** V4-444: each assembled head's provider, registered by ManagedHeadFactory, so the Playground sends
     *  through the head's own request builder rather than a hand-built copy of it. */
    val playgroundProviders = PlaygroundProviders()

    /** V4-133: POST /api/playground's ONE upstream probe, sending through [playgroundProviders]. Deviation, Oct 12,
     *  2026 (marlin): the send is recorded like a request and weighed by its command's budget (see
     *  UpstreamPlaygroundProbe). */
    val playground = UpstreamPlaygroundProbe(playgroundProviders)

    /** Nov 12, 2026: tells the operator when a command is on the last login that can serve its sessions, through
     *  splice.toml's `[daemon] alert_command` and the webhook saved here in [alerts]. */
    fun loginAlerting(srv: ControlServer, booted: BootedTopology): LoginAlerting = LoginAlerting(
        srv.accountRoster(),
        AlertCommand(booted.alertCommand),
        AlertDelivery(alerts, daemon.log, probeScope),
        daemon.statePaths.stateDir.resolve(LOGIN_ALERTS_FILE),
        daemon.log,
    )

    /** V4-134: the daemon's ONE console publisher, which every head reports through. */
    val console = ConsoleEventPublisher(
        ConsoleWiring.activityStores(daemon.statePaths, daemon.config),
        slots = SlotInstructions(teams, ConsoleWiring.sessionAddress(daemon.statePaths)),
        // V4-133 review: the budgets and alerts above, enforced on every head's turns (BudgetWiringPinTest).
        budgets = ConsoleWiring.budgetEnforcement(perfRows, budgets, alerts, probeScope, daemon.log),
        // V4-252: a SendMessage name is stored with the session holding it then (ConsoleActivityPublishTest).
        names = ConsoleWiring.nameHolders(daemon.statePaths),
        // 2026-10-19: the tools a session is inside, read from the wire, feed the registry's heard-from time.
        foreground = foregroundTools,
    )
}
