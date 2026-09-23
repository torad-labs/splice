// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (startLogin, pollLogin, loginStatusJson) — the console's
// sign-in slice: start a device or browser login off-request, then poll it to its terminal state.
package splice.accounts.signin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.http.JsonBody

public class LoginRoutes(
    private val resolver: AccountHeadResolver,
    /** V4-132: read at CALL time (never captured) — ConsoleWiring assigns the accounts port after
     *  this route object is constructed, the same discipline every other console port keeps. */
    private val accounts: ConsoleAccountsSource,
) {
    private val jsonBody = JsonBody()

    /** POST /api/auth/{head}/login (FEATURES.md §6): starts a device or browser login off-request
     *  and answers immediately with its STARTING view; the console polls [pollLogin] for the code,
     *  the link and the terminal state. Body: `{"label": "..."}`, optional. */
    public suspend fun startLogin(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val label = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        when (val started = accounts.startLogin(head.key, label, head.restart)) {
            is LoginStart.Started -> AccountReplies.respond(call, loginStatusJson(started.status))
            LoginStart.UnknownHead -> AccountReplies.respondError(call, "unknown head", HttpStatusCode.NotFound)
            is LoginStart.UnsupportedAuthKind -> AccountReplies.respondError(
                call,
                "no browser or device login for auth kind '${started.kind}'",
                HttpStatusCode.BadRequest,
            )
        }
    }

    /** GET /api/auth/{head}/login/{id} (FEATURES.md §6): polls one login started by [startLogin]. */
    public suspend fun pollLogin(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val status = accounts.pollLogin(call.parameters["id"].orEmpty())
        if (status == null) {
            AccountReplies.respondError(call, "unknown login id", HttpStatusCode.NotFound)
            return
        }
        AccountReplies.respond(call, loginStatusJson(status))
    }

    private fun loginStatusJson(status: LoginStatus): String = buildJsonObject {
        put("id", status.id)
        put("head", status.head)
        put("state", status.state.wire)
        put("user_code", status.userCode)
        put("verification_uri", status.verificationUri)
        put("browser_url", status.browserUrl)
        put("failure_reason", status.failureReason)
    }.toString()
}

/** Where a route reads the console's [ConsoleAccounts] port AT CALL TIME: the daemon assigns it after
 *  the routes are constructed, and a route that captured the value would answer unwired forever. */
public fun interface ConsoleAccountsSource {
    public operator fun invoke(): ConsoleAccounts?
}
