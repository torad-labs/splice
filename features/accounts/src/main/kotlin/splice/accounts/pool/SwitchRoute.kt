// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (switchAccount) — the pool's manual switch: a REAL pin in
// the head's account pool, taken from the next turn.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.core.auth.REFUSAL_FIELD
import splice.http.JsonBody

public class SwitchRoute(private val resolver: AccountHeadResolver) {
    private val jsonBody = JsonBody()

    /** POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch"): a REAL pin in
     *  [splice.upstream.credentials.AccountPool] — [select] tries it FIRST, ahead of the primary preference, from
     *  the next turn. Body: `{"label": "..."}`, required. A head with no pool (one login, or an
     *  unpooled kind) answers 400 naming it, never a silent no-op.
     *
     *  V4-423: an account the daemon has no credential for (a refused link, or a file that is gone) cannot serve a
     *  turn, so pinning it is a promise nothing keeps: it answers 409 with the account's own sentence and pins
     *  nothing. An account the pool does not list keeps the pin's 400. */
    public suspend fun switchAccount(call: ApplicationCall) {
        val target = pinTarget(call) ?: return
        val label = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        if (label.isNullOrBlank()) {
            AccountReplies.respondError(call, "body must name a 'label'", HttpStatusCode.BadRequest)
            return
        }
        val refusal = refusalFor(target.head, label)
        if (refusal != null) {
            AccountReplies.respond(
                call,
                buildJsonObject {
                    put("ok", false)
                    put("error", refusal)
                }.toString(),
                status = HttpStatusCode.Conflict,
            )
            return
        }
        val pinned = target.pin.pin(label)
        AccountReplies.respond(
            call,
            buildJsonObject {
                put("ok", pinned)
                if (!pinned) put("error", "unknown account label '$label'")
            }.toString(),
            status = if (pinned) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        )
    }

    /** DELETE /api/auth/{head}/switch: drops the pin, so [select] follows the pool's own policy from
     *  the next turn. Idempotent — `{"ok":true}` whether or not anything was pinned — and the same
     *  named 400 as the pin for a head with no pool. */
    public suspend fun unpinAccount(call: ApplicationCall) {
        val target = pinTarget(call) ?: return
        target.pin.unpin()
        AccountReplies.respond(call, buildJsonObject { put("ok", true) }.toString(), status = HttpStatusCode.OK)
    }

    /** Why [label] cannot be switched to, or null when it can: the pool lists it and the daemon has a credential
     *  for it. A refused link says so in its own words (V4-410's sentence, from the account's description); an
     *  account with no credential file gets the plain one. Not listed is not refused here: the pin answers that. */
    private suspend fun refusalFor(head: AccountHead, label: String): String? {
        val account = head.activePool?.view(null)?.accounts?.firstOrNull { it.label == label } ?: return null
        if (account.credential.present) return null
        return head.accountAuth?.descriptions()?.get(label)?.fields?.get(REFUSAL_FIELD)
            ?: "'$label' has no credential file; sign in to it first"
    }

    private suspend fun pinTarget(call: ApplicationCall): PinTarget? {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return null
        val pin = head.activePool as? HeadAccountPinSource
        if (pin == null) {
            AccountReplies.respondError(call, "head '$key' has no account pool to switch", HttpStatusCode.BadRequest)
            return null
        }
        return PinTarget(head, pin)
    }

    /** A resolved head and the pin its pool carries: the same object, seen as the two things the route needs. */
    private data class PinTarget(val head: AccountHead, val pin: HeadAccountPinSource)
}
