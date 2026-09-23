// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (switchAccount) — the pool's manual switch: a REAL pin in
// the head's account pool, taken from the next turn.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.http.JsonBody

public class SwitchRoute(private val resolver: AccountHeadResolver) {
    private val jsonBody = JsonBody()

    /** POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch"): a REAL pin in
     *  [splice.upstream.credentials.AccountPool] — [select] tries it FIRST, ahead of the primary preference, from
     *  the next turn. Body: `{"label": "..."}`, required. A head with no pool (one login, or an
     *  unpooled kind) answers 400 naming it, never a silent no-op. */
    public suspend fun switchAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val pin = head.pool as? HeadAccountPinSource
        if (pin == null) {
            AccountReplies.respondError(call, "head '$key' has no account pool to switch", HttpStatusCode.BadRequest)
            return
        }
        val label = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        if (label.isNullOrBlank()) {
            AccountReplies.respondError(call, "body must name a 'label'", HttpStatusCode.BadRequest)
            return
        }
        val pinned = pin.pin(label)
        AccountReplies.respond(
            call,
            buildJsonObject {
                put("ok", pinned)
                if (!pinned) put("error", "unknown account label '$label'")
            }.toString(),
            status = if (pinned) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        )
    }
}
