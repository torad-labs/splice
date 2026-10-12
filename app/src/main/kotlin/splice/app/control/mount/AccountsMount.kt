// NEW: LAYOUT-01 — the accounts capability's routes: auth status, sign-in, switching and editing a
// head's accounts, and the pool listing (features/accounts). V4-220 item 3: and the key store.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.claude.ClaudeLoginRoutes
import splice.accounts.edit.AccountEditRoutes
import splice.accounts.keys.KeyRoutes
import splice.accounts.keys.KeyStoreSource
import splice.accounts.order.AccountOrderRoute
import splice.accounts.pool.ACCOUNT_NAMES_FILE
import splice.accounts.pool.AccountNameRoute
import splice.accounts.pool.AccountNameStore
import splice.accounts.pool.AccountsRoute
import splice.accounts.pool.FirstAccounts
import splice.accounts.pool.SwitchRoute
import splice.accounts.signin.ConsoleAccountsSource
import splice.accounts.signin.LoginRoutes
import splice.accounts.status.AuthStatusRoutes
import splice.app.control.AccountHeadAdapter
import splice.app.control.AccountRoster
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.RosterAccountNames
import splice.app.control.api.HeadResolver
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.usage.QuotaReadFailureSource
import splice.core.util.LogSink
import splice.oauth.OAuthPrimaryAccount
import java.nio.file.Path

internal class AccountsMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    log: LogSink,
    /** Where splice keeps the names a person gave logins (labels only): its own state, never a credential file. */
    stateDir: Path? = null,
) {
    private val names = stateDir?.let { AccountNameStore(it.resolve(ACCOUNT_NAMES_FILE)) }
    private val logins = ClaudeLoginPlacesSource { ports.claudeLogins }
    private val accountHeads = AccountHeadAdapter.adapt(heads)
    private val accountResolver = AccountHeadAdapter.resolver(resolver)
    private val authStatusRoutes = AuthStatusRoutes(accountHeads, accountResolver)

    // Read at CALL time: ControlPlane assigns [ConsolePorts.accounts] after the server is constructed.
    private val loginRoutes = LoginRoutes(accountResolver, ConsoleAccountsSource { ports.accounts })
    private val switchRoute = SwitchRoute(accountResolver)
    private val accountEditRoutes = AccountEditRoutes(
        accountResolver,
        ConsoleAccountsSource { ports.accounts },
        ClaudeLoginPlacesSource { ports.claudeLogins },
    )

    // Read at CALL time, like [loginRoutes]' port: ControlPlane assigns [ConsolePorts.quotaReadFailures] later.
    private val readFailures = QuotaReadFailureSource { head, label -> ports.quotaReadFailures?.failure(head, label) }
    private val accountsRoute = AccountsRoute(accountHeads, OAuthFirstAccounts(), names, readFailures)
    private val nameRoute = names?.let(::AccountNameRoute)
    private val claudeRoutes = ClaudeLoginRoutes(logins, names, readFailures)
    private val accountOrderRoute = AccountOrderRoute(accountResolver)

    // Read at CALL time, like [loginRoutes]' port: ConsoleWiring assigns [ConsolePorts.keys] after construction.
    private val keyRoutes = KeyRoutes(accountHeads, KeyStoreSource { ports.keys }, log)

    /** The /api/auth body, for the daemon's own doctor (V4-230). */
    suspend fun authJson(): String = authStatusRoutes.authJson()

    /** How Requests and Usage name a recorded login: by the label this roster shows, never by an email a row kept. */
    fun recordedNames(): RosterAccountNames = RosterAccountNames(AccountRoster { accountsJson() }, logins, names)

    /** The route and in-process doctor read one authoritative roster at call time. */
    suspend fun accountsJson(): String {
        val providers = ports.declaredHeads?.invoke()?.mapValues { it.value.provider }.orEmpty()
        val logins = ports.claudeLogins
        val native = logins?.places().orEmpty()
        val carrying = native.map { it.head }.distinct().associateWith { logins?.carrying(it) }
        return accountsRoute.accountsJson(providers, native, carrying)
    }

    /** V4-132: EXPLICIT constant segments (login, switch, accounts/{label}) ahead of the `{action}`
     *  catch-all — Ktor's routing tree scores a literal segment over a parameter, so POST .../login
     *  wins over POST .../{action} regardless of registration order; pinned by a test rather than
     *  assumed. */
    fun register(route: Route) {
        route.get("/api/auth") { guard.guarded(call) { ControlReplies.respond(call, authStatusRoutes.authJson()) } }
        route.get("/api/accounts") {
            guard.guarded(call) {
                ControlReplies.respond(call, accountsJson())
            }
        }
        route.post("/api/auth/{head}/login") { guard.guarded(call) { loginRoutes.startLogin(call) } }
        route.get("/api/auth/{head}/login/{id}") {
            guard.guarded(call) { if (!claudeRoutes.poll(call)) loginRoutes.pollLogin(call) }
        }
        route.post("/api/claude-logins/{place}/login") { guard.guarded(call) { claudeRoutes.login(call) } }
        route.post("/api/claude-logins/{place}/refresh") {
            guard.guarded(call) {
                val providers = ports.declaredHeads?.invoke()?.mapValues { it.value.provider }.orEmpty()
                claudeRoutes.refresh(call, providers, accountHeads)
            }
        }
        route.post("/api/auth/{head}/login/{id}/code") { guard.guarded(call) { claudeRoutes.submit(call) } }
        route.get("/api/auth/{head}/order") {
            guard.guarded(call) { accountOrderRoute.get(call, ports.claudeLogins?.places().orEmpty()) }
        }
        route.put("/api/auth/{head}/order") { guard.guarded(call) { accountOrderRoute.set(call) } }
        route.post("/api/auth/{head}/switch") { guard.guarded(call) { switchRoute.switchAccount(call) } }
        route.delete("/api/auth/{head}/switch") { guard.guarded(call) { switchRoute.unpinAccount(call) } }
        route.delete("/api/auth/{head}/accounts/{label}") {
            guard.guarded(call) { accountEditRoutes.removeAccount(call) }
        }
        route.patch("/api/auth/{head}/accounts/{label}") {
            guard.guarded(call) { accountEditRoutes.relabelAccount(call) }
        }
        nameRoute?.let { names -> route.put("/api/accounts/names/{ref}") { guard.guarded(call) { names.set(call) } } }
        route.post("/api/auth/{head}/{action}") { guard.guarded(call) { authStatusRoutes.authAction(call) } }
        route.get("/api/keys") { guard.guarded(call) { keyRoutes.list(call) } }
        route.put("/api/keys/{name}") { guard.guarded(call) { keyRoutes.set(call) } }
        route.delete("/api/keys/{name}") { guard.guarded(call) { keyRoutes.unset(call) } }
    }

    /** A command's first OAuth account, as the OAuth file layer keeps it: its name, and whether its file is shared. */
    private class OAuthFirstAccounts(private val first: OAuthPrimaryAccount = OAuthPrimaryAccount()) : FirstAccounts {
        override fun name(kind: String, path: String): String? = oauth(kind)?.let { first.name(it, Path.of(path)) }

        override fun shared(kind: String, path: String): Boolean =
            oauth(kind)?.let { first.shared(it, Path.of(path)) } ?: true

        private fun oauth(kind: String): AuthKind.OAuth? = AuthKindRegistry.from(kind) as? AuthKind.OAuth
    }
}
