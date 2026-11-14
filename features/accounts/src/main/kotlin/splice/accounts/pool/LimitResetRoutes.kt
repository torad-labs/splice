// NEW: Nov 13, 2026: a login's limit resets, listed and spent on a person's word. That evening every Fable login on one
// machine ran out at about 6:24 PM CT with turns held until Sunday, and the only way to call a reset was by hand, in
// Claude Code, on each login. Marcos: "implement the reset feature (there are two, a fable reset and a weekly reset,
// lets make sure we support both)". These routes list how many of each kind a login has left and spend one; splice
// never spends one by itself.
package splice.accounts.pool

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.http.JsonBody

// why: the outcome a spend has when the provider confirmed it, the one a route answers 200 for.
public const val RESET_SPENT: String = "reset"

// why: the outcome of a spend asked for a kind the login's provider has no such reset for, which a route answers 400.
public const val RESET_UNKNOWN_KIND: String = "unknown_kind"

// why: the outcome of a spend whose answer did not say it was spent or refused; asking again sends the same request.
public const val RESET_UNCONFIRMED: String = "unconfirmed"

// why: the body field that names the kind of reset to spend.
private const val KIND_FIELD = "kind"

/** One kind of reset a login has: [kind] is what a person types, [name] what they read. [left] of [total] remain;
 *  [usableNow] says one can be spent now, and [notNow] why not when it cannot. [endsAt] is when the one that would be
 *  spent stops being offered, as the provider wrote it. */
public data class LoginResetKind(
    val kind: String,
    val name: String,
    val left: Int,
    val total: Int,
    val usableNow: Boolean,
    val notNow: String? = null,
    val endsAt: String? = null,
)

/** What a login's resets read as: its [kinds], or why it has none ([notOffered]) or could not be read ([refusal]). */
public data class LoginResets(
    val label: String,
    val kinds: List<LoginResetKind> = emptyList(),
    val offered: Boolean = true,
    val notOffered: String? = null,
    val refusal: String? = null,
)

/** One spend: its [outcome] ([RESET_SPENT] when spent), the sentence a person reads, how many of that kind are [left]
 *  and which limits it [cleared]. [readAgain] says the login's usage was read again after it, so the turns held for
 *  the limits it cleared go on. */
public data class LoginResetSpend(
    val label: String,
    val kind: String,
    val outcome: String,
    val said: String,
    val left: Int? = null,
    val cleared: List<String> = emptyList(),
    val readAgain: Boolean = false,
)

/** The limit resets of a head's logins, behind GET /api/auth/{head}/resets and POST /api/auth/{head}/reset. A SIBLING
 *  of [HeadAccountPoolSource] like [HeadAccountAskSource], found by the same checked cast; a head whose logins carry no
 *  resets has none. A spend is irreversible, so only a person's ask reaches [reset]. */
public interface HeadAccountResetSource {
    /** The labels of the logins that can hold resets, in the pool's order. */
    public fun resetLabels(): List<String>

    /** [label]'s resets, read now; null when no login on the head has that label. */
    public suspend fun resets(label: String): LoginResets?

    /** Spends one reset of [kind] on [label]; null when no login on the head has that label. */
    public suspend fun reset(label: String, kind: String): LoginResetSpend?
}

public class LimitResetRoutes(private val resolver: AccountHeadResolver) {
    private val jsonBody = JsonBody()

    /** GET /api/auth/{head}/resets, or `?label=` for one login: each login's resets, read from its provider now. */
    public suspend fun list(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val resets = head.pool as? HeadAccountResetSource
        if (resets == null || resets.resetLabels().isEmpty()) {
            AccountReplies.respondError(call, "head '$key' has no logins with limit resets", HttpStatusCode.BadRequest)
            return
        }
        val asked = call.request.queryParameters[AccountReplies.LABEL_FIELD]?.ifBlank { null }
        val rows = (asked?.let(::listOf) ?: resets.resetLabels()).map { label -> label to resets.resets(label) }
        if (asked != null && rows.single().second == null) {
            AccountReplies.respondError(call, "unknown account label '$asked'", HttpStatusCode.NotFound)
            return
        }
        val body = buildJsonObject {
            put("head", key)
            putJsonArray("logins") { rows.mapNotNull { it.second }.forEach { add(loginJson(it)) } }
        }
        AccountReplies.respond(call, body.toString())
    }

    /** POST /api/auth/{head}/reset, body `{"label": "...", "kind": "fable|weekly|full|5-hour"}`: spends one reset of
     *  that kind on that login, then reads the login's usage again so the head picks it up. 200 when the provider
     *  confirmed the spend, 409 with its sentence when it did not, 400 for a body or head that names nothing to spend
     *  and 404 for a label the head does not list. */
    public suspend fun spend(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val body = jsonBody.parse(call)
        val label = AccountReplies.stringField(body, AccountReplies.LABEL_FIELD)
        val kind = AccountReplies.stringField(body, KIND_FIELD)
        val resets = head.pool as? HeadAccountResetSource
        when {
            label.isNullOrBlank() -> refuse(call, "body must name a 'label'")
            kind.isNullOrBlank() -> refuse(call, "body must name a 'kind': fable, weekly, full or 5-hour")
            resets == null || resets.resetLabels().isEmpty() ->
                refuse(call, "head '$key' has no logins with limit resets")
            else -> answer(call, label, resets.reset(label, kind))
        }
    }

    private suspend fun refuse(call: ApplicationCall, refusal: String) {
        AccountReplies.respondError(call, refusal, HttpStatusCode.BadRequest)
    }

    private suspend fun answer(call: ApplicationCall, label: String, spend: LoginResetSpend?) {
        if (spend == null) {
            AccountReplies.respondError(call, "unknown account label '$label'", HttpStatusCode.NotFound)
        } else {
            AccountReplies.respond(call, spendJson(spend).toString(), status(spend.outcome))
        }
    }

    private fun status(outcome: String): HttpStatusCode = when (outcome) {
        RESET_SPENT -> HttpStatusCode.OK
        RESET_UNKNOWN_KIND -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.Conflict
    }

    private fun loginJson(resets: LoginResets): JsonObject = buildJsonObject {
        put(AccountReplies.LABEL_FIELD, resets.label)
        put("offered", resets.offered)
        resets.notOffered?.let { put("not_offered", it) }
        resets.refusal?.let { put("refusal", it) }
        putJsonArray("kinds") {
            resets.kinds.forEach { kind ->
                add(
                    buildJsonObject {
                        put(KIND_FIELD, kind.kind)
                        put("name", kind.name)
                        put("left", kind.left)
                        put("total", kind.total)
                        put("usable_now", kind.usableNow)
                        kind.notNow?.let { put("not_now", it) }
                        kind.endsAt?.let { put("ends_at", it) }
                    },
                )
            }
        }
    }

    private fun spendJson(spend: LoginResetSpend): JsonObject = buildJsonObject {
        put("ok", spend.outcome == RESET_SPENT)
        put(AccountReplies.LABEL_FIELD, spend.label)
        put(KIND_FIELD, spend.kind)
        put("outcome", spend.outcome)
        put("said", spend.said)
        spend.left?.let { put("left", it) }
        putJsonArray("cleared") { spend.cleared.forEach { add(it) } }
        put("read_again", spend.readAgain)
    }
}
