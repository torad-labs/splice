// NEW: masked auth.json introspection for `splice status`. Split from
// CodexAuthProvider.kt so the refresh ladder is not billed for display
// formatting (concentration HIGH, 2026-08-19). synthesizeExpiryMs stays
// on the provider (sh_01).
package splice.provider.codex.auth

import kotlinx.serialization.json.JsonObject
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.provider.codex.RefreshedTokens
import splice.upstream.credentials.TokenRenewal
import java.nio.file.Files
import java.nio.file.Path

private const val KIND = "chatgpt-oauth"
private const val FIELD_TOKENS = "tokens"
private const val FIELD_ACCESS_TOKEN = "access_token"
private const val FIELD_REFRESH_TOKEN = "refresh_token"
private const val FIELD_ACCOUNT_ID = "account_id"
private const val FIELD_LAST_REFRESH = "last_refresh"
private const val REFRESH_ERROR_SNIPPET = 160
private const val MAX_EMAIL_CHARS = 254

internal fun interface PersistRotation {
    operator fun invoke(
        raw: JsonObject,
        tokens: JsonObject,
        fresh: RefreshedTokens,
        access: String,
    ): RefreshOutcome
}

internal class CodexAuthDescribe(
    private val authPath: Path,
    private val authJson: CodexAuthJson,
    private val renewal: TokenRenewal,
    private val refreshCall: RefreshCall<RefreshedTokens>,
) {
    private val oauth = CodexOAuth()

    suspend fun describe(): AuthDescription {
        val out = mutableMapOf("auth_path" to authPath.toString())
        val presentOutcome = Cancellables.runCatchingCancellable {
            val raw = authJson.parseObject()
            val tokens = raw[FIELD_TOKENS] as? JsonObject
            val hasAccess = JsonScalars.str(tokens, FIELD_ACCESS_TOKEN)?.isNotEmpty() == true
            out.putAll(identityFields(tokens))
            JsonScalars.str(raw, FIELD_LAST_REFRESH)?.let { out[FIELD_LAST_REFRESH] = it }
            hasAccess
        }
        // A failed read is not "present"; its failure is consumed below via exceptionOrNull -> read_error.
        val present = presentOutcome.getOrElse { false }
        presentOutcome.exceptionOrNull()?.let { failure ->
            // DR-59: indeterminate is not logged-out — name it in the description instead.
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent) out["read_error"] = SafeFailureText.render(failure)
        }
        if (renewal.latch.isLatched(renewal.identity())) out["refresh_latched"] = INVALID_GRANT_REASON
        return AuthDescription(present = present, kind = KIND, fields = out)
    }

    private fun identityFields(tokens: JsonObject?): Map<String, String> = buildMap {
        JsonScalars.str(tokens, FIELD_ACCOUNT_ID)?.takeIf(String::isNotBlank)
            ?.let { put(ACCOUNT_REF_FIELD, AccountRef.of(it)) }
        accountEmail(tokens)?.let { put("email", it) }
    }

    private fun accountEmail(tokens: JsonObject?): String? {
        val email = JsonScalars.str(oauth.decodeJwtClaims(JsonScalars.str(tokens, FIELD_ID_TOKEN)), "email")
            ?.trim() ?: return null
        if (email.isEmpty() || email.length > MAX_EMAIL_CHARS) return null
        return email.takeIf { '@' in it && it.none(Char::isISOControl) }
    }

    // Runs holding the cross-process lock: re-read fresh, short-circuit if a peer already rotated,
    // else exchange. Split out of doRefresh so withLock's lambda stays a single call.
    internal suspend fun refreshLocked(priorAccess: String?, persist: PersistRotation): RefreshOutcome {
        val raw = Cancellables.runCatchingCancellable {
            authJson.parseObject()
        }.getOrElse { return RefreshOutcome.ReadFailed(it) }
        val tokens = raw[FIELD_TOKENS] as? JsonObject
        authJson.peerRotation(priorAccess, tokens)?.let { return it }
        return if (tokens == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(raw, tokens, persist)
    }

    // G15: InvalidGrant flows through the SAME rejectedOrAdopted() G1 re-read as any other rejection — a
    // "confirmed" invalid_grant (the one the renewal latches on) is one that survived that race check, exactly
    // matching the gap's "post-G1 re-read" requirement.
    private suspend fun exchangeRefreshToken(
        raw: JsonObject,
        tokens: JsonObject,
        persist: PersistRotation,
    ): RefreshOutcome {
        val refreshToken = JsonScalars.str(tokens, FIELD_REFRESH_TOKEN) ?: return RefreshOutcome.NoRefreshToken
        val used = JsonScalars.str(tokens, FIELD_ACCESS_TOKEN)
        // Guard the network hop too (Node wrapped the whole read+fetch): a thrown hop must degrade
        // to a typed outcome (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val attempt = Cancellables.runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrAdopted(refreshToken, used, "refresh response missing access_token")
                } else {
                    persist(raw, tokens, attempt.tokens, access)
                }
            }
            is RefreshAttempt.InvalidGrant -> rejectedOrAdopted(refreshToken, used, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> rejectedOrAdopted(refreshToken, used, attempt.detail)
            // No verdict on the token, so no confirming re-read: the turn's engine asks again later.
            is RefreshAttempt.Unavailable -> RefreshOutcome.Unavailable(attempt.detail)
        }
    }

    // G1, the confirming re-read: an endpoint rejection MIGHT be a stale-token race. When auth.json now holds a
    // DIFFERENT refresh token, a peer (the codex CLI, which takes no splice lock) rotated it while our POST was in
    // flight, and its pair is fresh: it is adopted as the current credential and nothing is posted. Posting the
    // peer's refresh token once, as this did until Nov 10, 2026, spent the token the codex CLI had just been issued
    // and could sign the CLI out. The adopted access token is served as it is and renewed later by the usual rules
    // (its proactive window, or a 401 naming that exact token). One POST per renewal, whatever the race.
    private fun rejectedOrAdopted(usedRefreshToken: String, usedAccess: String?, reason: String): RefreshOutcome {
        val reread = Cancellables.runCatchingCancellable { authJson.parseObject() }
        val rereadFailure = reread.exceptionOrNull()
        if (rereadFailure != null) {
            val detail = SafeFailureText.render(rereadFailure).take(REFRESH_ERROR_SNIPPET)
            return RefreshOutcome.Rejected("$reason; credential reread failed: $detail")
        }
        val newTokens = reread.getOrThrow()[FIELD_TOKENS] as? JsonObject
        val rotated = JsonScalars.str(newTokens, FIELD_REFRESH_TOKEN)?.let { it != usedRefreshToken } == true
        if (!rotated) return RefreshOutcome.Rejected(reason)
        return authJson.peerRotation(usedAccess, newTokens)
            ?: RefreshOutcome.Rejected("$reason; the credential file changed during the renewal")
    }
}
