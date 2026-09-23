// NEW: LAYOUT-01 — the accounts capability's routes: auth status, sign-in, switching and editing a
// head's accounts, and the pool listing (features/accounts).
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import splice.accounts.edit.AccountEditRoutes
import splice.accounts.pool.AccountsRoute
import splice.accounts.pool.SwitchRoute
import splice.accounts.signin.ConsoleAccountsSource
import splice.accounts.signin.LoginRoutes
import splice.accounts.status.AuthStatusRoutes
import splice.app.control.AccountHeadAdapter
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.api.HeadResolver

internal class AccountsMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val accountHeads = AccountHeadAdapter.adapt(heads)
    private val accountResolver = AccountHeadAdapter.resolver(resolver)
    private val authStatusRoutes = AuthStatusRoutes(accountHeads, accountResolver)

    // Read at CALL time: ControlPlane assigns [ConsolePorts.accounts] after the server is constructed.
    private val loginRoutes = LoginRoutes(accountResolver, ConsoleAccountsSource { ports.accounts })
    private val switchRoute = SwitchRoute(accountResolver)
    private val accountEditRoutes = AccountEditRoutes(accountResolver, ConsoleAccountsSource { ports.accounts })
    private val accountsRoute = AccountsRoute(accountHeads)

    /** V4-132: EXPLICIT constant segments (login, switch, accounts/{label}) ahead of the `{action}`
     *  catch-all — Ktor's routing tree scores a literal segment over a parameter, so POST .../login
     *  wins over POST .../{action} regardless of registration order; pinned by a test rather than
     *  assumed. */
    fun register(route: Route) {
        route.get("/api/auth") { guard.guarded(call) { ControlReplies.respond(call, authStatusRoutes.authJson()) } }
        route.get("/api/accounts") {
            guard.guarded(call) { ControlReplies.respond(call, accountsRoute.accountsJson()) }
        }
        route.post("/api/auth/{head}/login") { guard.guarded(call) { loginRoutes.startLogin(call) } }
        route.get("/api/auth/{head}/login/{id}") { guard.guarded(call) { loginRoutes.pollLogin(call) } }
        route.post("/api/auth/{head}/switch") { guard.guarded(call) { switchRoute.switchAccount(call) } }
        route.delete("/api/auth/{head}/accounts/{label}") {
            guard.guarded(call) { accountEditRoutes.removeAccount(call) }
        }
        route.patch("/api/auth/{head}/accounts/{label}") {
            guard.guarded(call) { accountEditRoutes.relabelAccount(call) }
        }
        route.post("/api/auth/{head}/{action}") { guard.guarded(call) { authStatusRoutes.authAction(call) } }
    }
}
