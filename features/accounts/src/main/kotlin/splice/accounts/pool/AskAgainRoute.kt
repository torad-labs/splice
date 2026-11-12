// NEW: Try again on a login its provider refused (Nov 11, 2026). A card that read "Blocked until its organization
// allows subscription access" offered only Sign in again, in its menu. Marcos, that day: "I did sign in again because
// it said it was blocked, I realized my payument failed, so I paid again". Nothing on the card let him say the cause
// was fixed, and splice asked the login again only when its hold ran out and a turn happened to reach it.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.http.JsonBody

public class AskAgainRoute(private val resolver: AccountHeadResolver) {
    private val jsonBody = JsonBody()

    /** POST /api/auth/{head}/ask, body `{"label": "..."}`: splice lets go of what holds that login out for its
     *  credential, a refusal's hold or a block its owner has to lift, so the next turn that reaches it asks its
     *  provider again and the provider's answer decides. It asks the provider nothing itself: only a turn does, and a
     *  login still refused reads blocked again from that turn's answer. A login nothing holds answers ok too. Like the
     *  switch beside it, a body with no label, a label the pool does not list and a head with no pool are each a 400
     *  that names what was wrong. */
    public suspend fun askAgain(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val label = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        val pool = head.activePool as? HeadAccountAskSource
        val refusal = when {
            label.isNullOrBlank() -> "body must name a 'label'"
            pool == null -> "head '$key' has no account pool to ask"
            !pool.askAgain(label) -> "unknown account label '$label'"
            else -> null
        }
        if (refusal != null) {
            AccountReplies.respondError(call, refusal, HttpStatusCode.BadRequest)
            return
        }
        AccountReplies.respond(call, buildJsonObject { put("ok", true) }.toString())
    }
}
