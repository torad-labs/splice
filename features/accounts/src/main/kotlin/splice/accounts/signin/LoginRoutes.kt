// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (startLogin, pollLogin, loginStatusJson) — the console's
// sign-in slice: start a device or browser login off-request, then poll it to its terminal state.
package splice.accounts.signin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
            is LoginStart.Started -> AccountReplies.respond(call, LoginStatusJson.json(started.status))
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
        val head = resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val status = accounts.pollLogin(call.parameters["id"].orEmpty())
            ?.takeIf { it.head == head.key }
        if (status == null) {
            AccountReplies.respondError(call, "unknown login id", HttpStatusCode.NotFound)
            return
        }
        AccountReplies.respond(call, LoginStatusJson.json(status))
    }

    /** DELETE /api/auth/{head}/login/{id}: ends a sign-in [startLogin] or a Claude Code login place began, and
     *  answers once it has ended, with how: cancelled, or its own end when it finished first. The page's Cancel
     *  waits for this answer, so a card that reads cancelled is one splice holds no sign-in for. */
    public suspend fun cancelLogin(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val id = call.parameters["id"].orEmpty()
        val ended = accounts.pollLogin(id)?.takeIf { it.head == head.key }?.let { accounts.cancelLogin(id) }
        if (ended == null) {
            AccountReplies.respondError(call, "unknown login id", HttpStatusCode.NotFound)
            return
        }
        AccountReplies.respond(call, LoginStatusJson.json(ended))
    }

    /** GET /api/auth/logins: every sign-in still open on this daemon, each with the command it is for. A page opened
     *  after one began reads them here, so it shows a sign-in it did not start and can end it. */
    public suspend fun openLogins(call: ApplicationCall) {
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val open = buildJsonArray {
            accounts.openLogins().forEach { status ->
                add(JsonObject(LoginStatusJson.fields(status) + buildJsonObject { put("head", status.head) }))
            }
        }
        AccountReplies.respond(call, buildJsonObject { put("logins", open) }.toString())
    }
}

/** Identical OAuth and native CLI progress payloads, never subprocess output or credential bytes. */
internal object LoginStatusJson {
    fun json(status: LoginStatus): String = fields(status).toString()

    fun fields(status: LoginStatus): JsonObject = buildJsonObject {
        put("id", status.id)
        put("state", status.state.wire)
        put("user_code", status.prompt.userCode)
        put("verification_uri", status.prompt.verificationUri)
        put("browser_url", status.prompt.browserUrl)
        put("paste_code", status.prompt.pasteBack)
        put("failure_reason", status.failureReason)
        put("failure_kind", status.failureKind?.wire)
        put("label", status.label)
    }
}

/** Where a route reads the console's [ConsoleAccounts] port AT CALL TIME: the daemon assigns it after
 *  the routes are constructed, and a route that captured the value would answer unwired forever. */
public fun interface ConsoleAccountsSource {
    public operator fun invoke(): ConsoleAccounts?
}
