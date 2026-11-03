// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (switchAccount) — the pool's manual switch: a REAL pin in
// the head's account pool, taken from the next turn.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.core.auth.REFUSAL_FIELD
import splice.http.JsonBody

private const val SESSION_FIELD = "session"

public class SwitchRoute(private val resolver: AccountHeadResolver) {
    private val jsonBody = JsonBody()

    /** POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch"): a REAL pin in
     *  [splice.upstream.credentials.AccountPool] — [select] tries it FIRST, ahead of the primary preference, from
     *  the next turn. Body: `{"label": "...", "session": "..."}`: the label is required, and a session id pins that
     *  session alone while the rest of the head keeps its policy (spec section 11). Without one the pin is head-wide.
     *  A head with no pool (one login, or an
     *  unpooled kind) answers 400 naming it, never a silent no-op.
     *
     *  V4-423: an account the daemon has no credential for (a refused link, or a file that is gone) cannot serve a
     *  turn, so pinning it is a promise nothing keeps: it answers 409 with the account's own sentence and pins
     *  nothing. An account the pool does not list keeps the pin's 400.
     *
     *  Nov 3, 2026 (Marcos: "clicking 'use now' also didnt work"): an account the provider holds for every model, its
     *  plan window named spent, serves no turn until that reset, so pinning it is the same empty promise: a 409 that
     *  names the window and the reset, and nothing pinned. An account held for one model's week serves every other
     *  model, so it pins, and the answer names each model it cannot serve yet and until when (`models_held`), so a turn
     *  of that model that still goes elsewhere is no surprise. */
    public suspend fun switchAccount(call: ApplicationCall) {
        val target = pinTarget(call) ?: return
        val body = jsonBody.parse(call)
        val label = AccountReplies.stringField(body, AccountReplies.LABEL_FIELD)
        val session = AccountReplies.stringField(body, SESSION_FIELD)?.takeIf(String::isNotBlank)
        if (label.isNullOrBlank()) {
            AccountReplies.respondError(call, "body must name a 'label'", HttpStatusCode.BadRequest)
            return
        }
        val account = target.head.activePool?.view(null)?.accounts?.firstOrNull { it.label == label }
        val refusal = account?.let { refusalFor(target.head, it) }
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
        val pinned = target.pin.pin(label, session)
        AccountReplies.respond(
            call,
            buildJsonObject {
                put("ok", pinned)
                if (!pinned) put("error", "unknown account label '$label'")
                val held = account?.quota?.holds?.models.orEmpty().takeIf { pinned }.orEmpty()
                if (held.isNotEmpty()) {
                    putJsonArray("models_held") { HeldWords().modelWeeks(this, held) }
                }
            }.toString(),
            status = if (pinned) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        )
    }

    /** DELETE /api/auth/{head}/switch: drops the pin, so [select] follows the pool's own policy from
     *  the next turn. Idempotent — `{"ok":true}` whether or not anything was pinned — and the same
     *  named 400 as the pin for a head with no pool. `?session=<id>` drops that
     *  session's pin alone and leaves the head-wide one. */
    public suspend fun unpinAccount(call: ApplicationCall) {
        val target = pinTarget(call) ?: return
        target.pin.unpin(call.request.queryParameters[SESSION_FIELD]?.takeIf(String::isNotBlank))
        AccountReplies.respond(call, buildJsonObject { put("ok", true) }.toString(), status = HttpStatusCode.OK)
    }

    /** Why [account] cannot be switched to, or null when it can: the daemon has a credential for it, and its provider
     *  holds it for no window of the whole login. A refused link says so in its own words (V4-410's sentence, from the
     *  account's description); an account with no credential file gets the plain one; a spent plan names its window
     *  and when it serves again. Not listed is not refused here: the pin answers that. */
    private suspend fun refusalFor(head: AccountHead, account: HeadAccountView): String? {
        val label = account.label
        if (!account.credential.present) {
            return head.accountAuth?.descriptions()?.get(label)?.fields?.get(REFUSAL_FIELD)
                ?: HeldWords().noCredential(label)
        }
        return account.quota.holds.wholeLogin?.let { plan -> HeldWords().planSpent(label, plan) }
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
