// NEW: LAYOUT-01 — the usage capability's routes: quota, perf, economics, budgets, alerts and the
// statusline (features/usage).
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withContext
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.UsageHeadAdapter
import splice.app.control.api.HeadResolver
import splice.app.sources.PerfStatsSource
import splice.core.config.ConfigService
import splice.core.perf.KeptHistory
import splice.core.version.ClientVersionTracker
import splice.head.perf.TurnKeptRoutes
import splice.models.roster.DeclaredHeads
import splice.upstream.codemode.ProcessDispatchers
import splice.usage.alerts.AlertRoutes
import splice.usage.alerts.AlertSource
import splice.usage.budgets.BudgetRoutes
import splice.usage.budgets.BudgetSource
import splice.usage.perf.PerfPayloads
import splice.usage.quota.UsagePayloads
import splice.usage.statusline.StatuslineRoute

// why: the perf rows /api/perf returns when the request names no ?tail — the recent end the console's
// table renders first, never the whole ledger.
private const val DEFAULT_PERF_TAIL = 200

/** What a pre-0.4.0 session's status line prints: it sends no bearer, and 0.4.0 does not answer it with data. */
private const val STATUSLINE_RELAUNCH = "splice updated: relaunch this session for its status line"

internal class UsageMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    private val config: ConfigService,
    clientVersions: ClientVersionTracker,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val liveTotals = heads.mapNotNull { (key, managed) ->
        (managed.sources.perf as? PerfStatsSource)?.sessionTotals?.let { key to it }
    }.toMap()
    private val usageHeads = UsageHeadAdapter.heads(heads, ClaudeLoginPlacesSource { ports.claudeLogins })
    private val usageLookup = UsageHeadAdapter.lookup(
        resolver,
        DeclaredHeads { ports.declaredHeads?.invoke().orEmpty() },
    )
    private val usagePayloads = UsagePayloads(usageHeads, config)
    private val perfPayloads = PerfPayloads(usageHeads)
    private val statuslineRoute = StatuslineRoute(usageLookup, config, clientVersions)

    // File scans, cache-monitor waits and folds must not occupy Netty's control request event loops.
    private val fileIo = ProcessDispatchers().io()
    private val kept = KeptHistory { config.getConfig().historyWindow }
    private val usageReads = UsageReadRoutes(usageHeads, usageLookup, fileIo, guard, kept)

    // Settings > Your data's one window, with its own wiring: see HistoryWiring.
    private val history = HistoryWiring(ports, config, guard, fileIo, heads)

    // V4-133 (FEATURES.md §5/§6): read at CALL time through the same BudgetSource/AlertSource
    // discipline every other console port keeps — see ConsolePorts.
    private val budgetRoutes = BudgetRoutes(BudgetSource { ports.budgets }, config, usageLookup)
    private val alertRoutes = AlertRoutes(AlertSource { ports.alerts })

    fun register(route: Route) {
        usageReads.register(route)
        route.get("/api/usage") { guard.guarded(call) { ControlReplies.respond(call, usagePayloads.usageJson()) } }
        route.post("/api/usage/probe") {
            guard.guarded(call) { ControlReplies.respond(call, usagePayloads.probeNowJson()) }
        }
        route.get("/api/perf") {
            guard.guarded(call) {
                val result = withContext(fileIo) {
                    perfPayloads.perfJson(ControlReplies.tail(call, DEFAULT_PERF_TAIL))
                }
                ControlReplies.respond(call, result)
            }
        }
        route.get("/api/perf/summary") {
            guard.guarded(call) { withContext(fileIo) { perfPayloads.summary(call) } }
        }
        route.get("/api/kept/turns") {
            guard.guarded(call) {
                withContext(fileIo) { TurnKeptRoutes(ports.turnStatistics).kept() }.send(call)
            }
        }
        route.delete("/api/kept/turns") {
            guard.guarded(call) {
                withContext(fileIo) { TurnKeptRoutes(ports.turnStatistics, liveTotals).delete() }.send(call)
            }
        }
        history.register(route)
        route.get("/api/budgets") { guard.guarded(call) { budgetRoutes.read(ports.budgetSpending).send(call) } }
        route.put("/api/budgets") {
            guard.guarded(call) { budgetRoutes.write(call.receiveText(), ports.budgetSpending).send(call) }
        }
        route.get("/api/alerts") { guard.guarded(call) { alertRoutes.read().send(call) } }
        route.put("/api/alerts") { guard.guarded(call) { alertRoutes.write(call.receiveText()).send(call) } }
        route.post("/api/alerts/test") { guard.guarded(call) { alertRoutes.test().send(call) } }
        // A SESSION's statusline command calls these, so its turn key opens them (with the resume hook).
        route.post("/statusline/{head}") {
            guard.guarded(call, Door.SESSION, STATUSLINE_RELAUNCH) { statuslineRoute.statusline(call) }
        }
        route.get("/statusline/{head}") {
            guard.guarded(call, Door.SESSION, STATUSLINE_RELAUNCH) { statuslineRoute.statusline(call) }
        }
    }
}
