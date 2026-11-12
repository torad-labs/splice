// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). PUT /api/accounts/names/{ref} {"name": "..."} names any
// login by its ref: the one route behind Accounts' Name it, its Rename, and the name typed when a login is added.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import splice.accounts.AccountReplies
import splice.http.JsonBody

public class AccountNameRoute(private val store: AccountNameStore) {
    private val body = JsonBody()

    public suspend fun set(call: ApplicationCall) {
        val ref = call.parameters["ref"].orEmpty()
        val name = AccountReplies.stringField(body.parse(call), "name")
        if (name == null) {
            AccountReplies.respondError(call, "body must contain a 'name' string", HttpStatusCode.BadRequest)
            return
        }
        try {
            store.set(ref, name)
        } catch (refused: AccountNameRefused) {
            AccountReplies.respondError(call, refused.message.orEmpty(), HttpStatusCode.BadRequest)
            return
        }
        AccountReplies.respond(call, "{}")
    }
}
