// NEW: server mode (docs/specs/server-mode.md, F5). The control plane's routes on a server's usage ledger, its export
// and its budgets, behind the management key on loopback, which is how `splice server usage` and `splice server budget`
// reach the daemon that keeps them:
//
//   GET    /api/server/usage/rows              every row of a window: since or from and to, person
//   GET    /api/server/usage                   the usage report: by, since or from and to, format (json, csv, md)
//   POST   /api/server/usage/export            export the ledger to `[server.export]` now
//   GET    /api/server/budgets                 every budget, with what was spent against it
//   PUT    /api/server/budgets                 set one: {"scope", "name", "daily_usd"?, "monthly_usd"?, "action"?}
package splice.app.control.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.mount.ControlGuard
import splice.http.JsonBody

internal class ServerLedgerRoutes(private val replies: ServerModeReplies, private val guard: ControlGuard) {
    private val jsonBody = JsonBody()

    fun register(route: Route) {
        route.get("/api/server/usage/rows") { guard.guarded(call) { rows(call) } }
        route.get("/api/server/usage") { guard.guarded(call) { report(call) } }
        route.post("/api/server/usage/export") { guard.guarded(call) { export(call) } }
        route.get("/api/server/budgets") { guard.guarded(call) { budgets(call) } }
        route.put("/api/server/budgets") { guard.guarded(call) { setBudget(call) } }
    }

    private suspend fun rows(call: ApplicationCall) {
        val mode = replies.running(call) ?: return
        replies.say(call, mode.books.usageApi.rows(call.request.queryParameters))
    }

    private suspend fun report(call: ApplicationCall) {
        val mode = replies.running(call) ?: return
        replies.say(call, mode.books.usageApi.report(call.request.queryParameters))
    }

    private suspend fun export(call: ApplicationCall) {
        val mode = replies.running(call) ?: return
        replies.say(call, mode.books.usageExport.now())
    }

    private suspend fun budgets(call: ApplicationCall) {
        val mode = replies.running(call) ?: return
        replies.say(call, mode.books.budgetApi.list())
    }

    private suspend fun setBudget(call: ApplicationCall) {
        val mode = replies.running(call) ?: return
        replies.say(call, mode.books.budgetApi.set(jsonBody.parse(call)))
    }
}
