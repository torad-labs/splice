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
import splice.core.config.Knob
import splice.core.version.ClientVersionTracker
import splice.head.perf.HistoryDays
import splice.head.perf.HistoryRoutes
import splice.head.perf.HistoryWindowStore
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
    private val usageReads = UsageReadRoutes(usageHeads, usageLookup, fileIo, guard, config.getConfig().historyWindow)

    // V4-133 (FEATURES.md §5/§6): read at CALL time through the same BudgetSource/AlertSource
    // discipline every other console port keeps — see ConsolePorts.
    // The one window on Settings > Your data. The scan cache lives here, across calls; the routes
    // are built per call like every other console port, so a port wired after mount is still read.
    private val historyDays = HistoryDays()
    private val historyWindow = HistoryWindowStore { text ->
        val written = config.patch(mapOf(Knob.HISTORY_RETENTION_DAYS.key to text))
        written.rejected.values.firstOrNull() ?: written.notPersisted
    }

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
        registerHistory(route)
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

    /** Settings > Your data's one window: what is held, what a shorter one would delete, and the
     *  save that deletes exactly that. */
    private fun registerHistory(route: Route) {
        route.get("/api/history") {
            guard.guarded(call) {
                val days = call.request.queryParameters["days"]
                withContext(fileIo) { history().read(config.getConfig().historyWindow, days) }.send(call)
            }
        }
        route.put("/api/history") {
            guard.guarded(call) {
                val asked = call.receiveText()
                withContext(fileIo) { history().save(asked) }.send(call)
            }
        }
    }

    private fun history() = HistoryRoutes(ports.turnStatistics, historyWindow, inventory = historyDays)
}
