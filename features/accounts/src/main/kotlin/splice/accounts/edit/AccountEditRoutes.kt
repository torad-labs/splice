// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (removeAccount, relabelAccount, respondMutation) — the
// console's account-edit slice: remove a pooled account, or give it a new label.
package splice.accounts.edit

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccountsSource
import splice.http.JsonBody

public class AccountEditRoutes(
    private val resolver: AccountHeadResolver,
    /** Read at CALL time, like the sign-in routes' port — see [ConsoleAccountsSource]. */
    private val accounts: ConsoleAccountsSource,
) {
    private val jsonBody = JsonBody()

    /** DELETE /api/auth/{head}/accounts/{label} (FEATURES.md §6): removes a pooled account. */
    public suspend fun removeAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val label = call.parameters[AccountReplies.LABEL_FIELD].orEmpty()
        respondMutation(call, accounts.removeAccount(head.key, label))
    }

    /** PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6): relabels a pooled account. Body:
     *  `{"label": "<new label>"}`, required — the NEW label; the path segment names the old one. */
    public suspend fun relabelAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val accounts = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
        val label = call.parameters[AccountReplies.LABEL_FIELD].orEmpty()
        val newLabel = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        if (newLabel.isNullOrBlank()) {
            AccountReplies.respondError(call, "body must name a new 'label'", HttpStatusCode.BadRequest)
            return
        }
        respondMutation(call, accounts.relabelAccount(head.key, label, newLabel))
    }

    private suspend fun respondMutation(call: ApplicationCall, result: AccountMutation) {
        when (result) {
            AccountMutation.Ok -> AccountReplies.respond(call, buildJsonObject { put("ok", true) }.toString())
            AccountMutation.UnknownHead -> AccountReplies.respondError(call, "unknown head", HttpStatusCode.NotFound)
            is AccountMutation.Refused -> AccountReplies.respondError(call, result.reason, HttpStatusCode.BadRequest)
        }
    }
}
