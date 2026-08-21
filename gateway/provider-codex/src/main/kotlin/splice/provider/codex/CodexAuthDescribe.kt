// NEW: masked auth.json introspection for `splice status`. Split from
// CodexAuthProvider.kt so the refresh ladder is not billed for display
// formatting (concentration HIGH, 2026-08-19). synthesizeExpiryMs stays
// on the provider (sh_01).
package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

private const val KIND = "chatgpt-oauth"
private const val MASK_KEEP = 4
private const val FIELD_TOKENS = "tokens"
private const val FIELD_ACCESS_TOKEN = "access_token"
private const val FIELD_REFRESH_TOKEN = "refresh_token"
private const val FIELD_ACCOUNT_ID = "account_id"
private const val FIELD_LAST_REFRESH = "last_refresh"
private const val REFRESH_ERROR_SNIPPET = 160

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
    private val authFile: CodexAuthFile,
    private val invalidGrantLatch: InvalidGrantLatch,
    private val log: LogSink,
    private val refreshCall: RefreshCall<RefreshedTokens>,
) {
    suspend fun describe(): AuthDescription {
        val out = mutableMapOf("auth_path" to authPath.toString())
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = Cancellables.runCatchingCancellable {
            if (!Files.exists(authPath)) return@runCatchingCancellable false
            val raw = authJson.parseObject()
            val tokens = raw[FIELD_TOKENS] as? JsonObject
            val hasAccess = JsonScalars.str(tokens, FIELD_ACCESS_TOKEN)?.isNotEmpty() == true
            val acct = JsonScalars.str(tokens, FIELD_ACCOUNT_ID).orEmpty()
            out["account_id_masked"] =
                if (acct.isNotEmpty()) "${acct.take(MASK_KEEP)}…${acct.takeLast(MASK_KEEP)}" else ""
            JsonScalars.str(raw, FIELD_LAST_REFRESH)?.let { out[FIELD_LAST_REFRESH] = it }
            hasAccess
        }.getOrDefault(false)
        val mtime = authFile.codexAuthMtimeOrNull(authPath, log)
        if (invalidGrantLatch.isLatched(mtime)) out["refresh_latched"] = INVALID_GRANT_REASON
        return AuthDescription(present = present, kind = KIND, fields = out)
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

    // G15: InvalidGrant flows through the SAME rejectedOrRetry() G1 reread-once dance as any other
    // rejection — a "confirmed" invalid_grant (the one doRefresh() latches on) is one that survived
    // that race check, exactly matching the gap's "post-G1 re-read" requirement.
    private suspend fun exchangeRefreshToken(
        raw: JsonObject,
        tokens: JsonObject,
        persist: PersistRotation,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        val refreshToken = JsonScalars.str(tokens, FIELD_REFRESH_TOKEN) ?: return RefreshOutcome.NoRefreshToken
        // Guard the network hop too (Node wrapped the whole read+fetch): a thrown hop must degrade
        // to a typed outcome (→ UpstreamFailed → re-prompt), not blow through SingleFlight uncaught.
        val attempt = Cancellables.runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrRetry(refreshToken, persist, allowRereadRetry, "refresh response missing access_token")
                } else {
                    persist(raw, tokens, attempt.tokens, access)
                }
            }
            is RefreshAttempt.InvalidGrant ->
                rejectedOrRetry(refreshToken, persist, allowRereadRetry, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied ->
                rejectedOrRetry(refreshToken, persist, allowRereadRetry, attempt.detail)
        }
    }

    // Bounded one-shot retry: an endpoint rejection MIGHT be a stale-token race — if disk now shows a
    // DIFFERENT refresh token (a peer rotated between our read and the POST landing), retry once
    // against it. Capped at exactly one extra POST (the retry passes allowRereadRetry=false); never
    // loops, and never re-POSTs the identical dead token (the disk-differs gate).
    private suspend fun rejectedOrRetry(
        usedRefreshToken: String,
        persist: PersistRotation,
        allowRereadRetry: Boolean,
        reason: String,
    ): RefreshOutcome {
        if (!allowRereadRetry) return RefreshOutcome.Rejected(reason)
        val reread = Cancellables.runCatchingCancellable { authJson.parseObject() }
        val rereadFailure = reread.exceptionOrNull()
        if (rereadFailure != null) {
            val detail = rereadFailure.message.orEmpty().take(REFRESH_ERROR_SNIPPET)
            return RefreshOutcome.Rejected("$reason; credential reread failed: $detail")
        }
        val fresh = reread.getOrThrow()
        val newTokens = fresh[FIELD_TOKENS] as? JsonObject
        val rotatedToken = JsonScalars.str(newTokens, FIELD_REFRESH_TOKEN)?.takeUnless { it == usedRefreshToken }
        return if (newTokens != null && rotatedToken != null) {
            exchangeRefreshToken(fresh, newTokens, persist, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }
}
