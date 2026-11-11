// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (removeAccount, relabelAccount, respondMutation) — the
// console's account-edit slice: remove a pooled account, or give it a new label.
package splice.accounts.edit

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.AccountReplies
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.pool.PRIMARY_ID
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccountsSource
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.util.Cancellables
import splice.http.JsonBody

public class AccountEditRoutes(
    private val resolver: AccountHeadResolver,
    /** Read at CALL time, like the sign-in routes' port — see [ConsoleAccountsSource]. */
    private val accounts: ConsoleAccountsSource,
    private val native: ClaudeLoginPlacesSource = ClaudeLoginPlacesSource { null },
) {
    private val jsonBody = JsonBody()

    private sealed class Target {
        data class Pool(val id: String) : Target()
        data class Native(val place: ClaudeLoginPlaceId, val owner: ClaudeLoginPlaces) : Target()
    }

    /** Legacy labels are safe only when they cannot name a native place as well as a pooled login. */
    private suspend fun target(call: ApplicationCall, head: AccountHead, label: String): Target? {
        val owner = native()
        val places = owner?.places().orEmpty().filter { it.head == head.key }
        val kind = call.request.queryParameters["target_kind"]
        val id = call.request.queryParameters["target_id"]
        return when {
            kind == null && id == null -> legacyTarget(
                call,
                label,
                head.auth.describe().kind == "client" || places.any { it.id.wire == label },
            )
            id != label || kind !in listOf("native", "pool") -> {
                AccountReplies.respondError(
                    call,
                    "target_kind and target_id must name this login",
                    HttpStatusCode.BadRequest,
                )
                null
            }
            kind == "pool" -> Target.Pool(label)
            else -> nativeTarget(call, owner, places.singleOrNull { it.id.wire == id }?.id)
        }
    }

    private suspend fun legacyTarget(call: ApplicationCall, label: String, requiresTarget: Boolean): Target? {
        if (!requiresTarget) return Target.Pool(label)
        AccountReplies.respondError(
            call,
            "'$label' names a native login place; specify its target kind and id before editing it",
            HttpStatusCode.Conflict,
        )
        return null
    }

    private suspend fun nativeTarget(
        call: ApplicationCall,
        owner: ClaudeLoginPlaces?,
        place: ClaudeLoginPlaceId?,
    ): Target? {
        if (place != null && owner != null) return Target.Native(place, owner)
        AccountReplies.respondError(call, "native login target is missing or ambiguous", HttpStatusCode.Conflict)
        return null
    }

    /** DELETE /api/auth/{head}/accounts/{label} (FEATURES.md §6): removes a pooled account. */
    public suspend fun removeAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val label = call.parameters[AccountReplies.LABEL_FIELD].orEmpty()
        val target = target(call, head, label) ?: return
        val result = when (target) {
            is Target.Pool -> {
                val port = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
                port.removeAccount(head.key, target.id).also { rediscover(head, target, it) }
            }
            is Target.Native -> target.owner.remove(target.place)
        }
        respondMutation(call, result)
    }

    /** A ChatGPT, Grok, Kimi or Muse head builds its pool once, when it starts, so it went on holding a login whose
     *  file was just removed or renamed: the roster listed it as signed out, with a Sign in that would file it again
     *  under the label just removed, until the daemon next restarted (Marcos, Nov 11, 2026: "I had issues before like
     *  this adding new accounts, removing"). The head starts again and finds its pool as it is on disk, the way it
     *  does when a sign-in lands ([splice.accounts.signin.HeadRestart]). A Claude head follows its folders by
     *  itself, and a head's first login is read where it is, so neither needs it. A head that does not come back
     *  is its own start's to report: the file is gone either way. */
    private suspend fun rediscover(head: AccountHead, target: Target.Pool, answered: AccountMutation) {
        val built = target.id != PRIMARY_ID && head.auth.describe().kind != CLIENT_AUTH_KIND
        if (answered != AccountMutation.Ok || !built) return
        Cancellables.discard(
            Cancellables.runCatchingBestEffort { head.restart.restart() },
            "the login is removed or renamed on disk whether or not the head comes back",
        )
    }

    /** PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6): relabels a pooled account. Body:
     *  `{"label": "<new label>"}`, required — the NEW label; the path segment names the old one. */
    public suspend fun relabelAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val label = call.parameters[AccountReplies.LABEL_FIELD].orEmpty()
        val newLabel = AccountReplies.stringField(jsonBody.parse(call), AccountReplies.LABEL_FIELD)
        if (newLabel.isNullOrBlank()) {
            AccountReplies.respondError(call, "body must name a new 'label'", HttpStatusCode.BadRequest)
            return
        }
        val target = target(call, head, label) ?: return
        rename(call, head, target, newLabel)
    }

    private suspend fun rename(call: ApplicationCall, head: AccountHead, target: Target, newLabel: String) {
        val result = when (target) {
            is Target.Pool -> {
                val port = accounts() ?: return AccountReplies.respondUnwired(call, AccountReplies.ACCOUNTS_PORT)
                port.relabelAccount(head.key, target.id, newLabel).also { rediscover(head, target, it) }
            }
            is Target.Native -> target.owner.relabel(target.place, newLabel)
        }
        respondMutation(call, result)
    }

    private suspend fun respondMutation(call: ApplicationCall, result: AccountMutation) {
        when (result) {
            AccountMutation.Ok -> AccountReplies.respond(call, "{}")
            AccountMutation.UnknownHead -> AccountReplies.respondError(call, "unknown head", HttpStatusCode.NotFound)
            // The head is real, so this is a bad request about it rather than a missing head.
            is AccountMutation.UnsupportedAuthKind -> AccountReplies.respondError(
                call,
                "this command's accounts cannot be edited here (auth kind '${result.kind}')",
                HttpStatusCode.BadRequest,
            )
            is AccountMutation.Refused -> AccountReplies.respondError(call, result.reason, HttpStatusCode.BadRequest)
        }
    }
}
