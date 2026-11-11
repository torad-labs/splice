// NEW: masked auth.json introspection for `splice status` plus the refresh-exchange ladder — the
// same pair of responsibilities CodexAuthDescribe.kt owns, under the same name (renamed from
// GrokAuthFile, review 2026-08-28 PR 99: codex's *AuthFile is a single-method mtime probe, so one
// name meant two different-sized responsibilities and a reader who had learned one provider's
// collaborator shape could not predict the next). The mtime probe that rode along here moved to the
// shared TokenRenewal with the rest of the renewal ladder (audit, Nov 9, 2026). Was a file-private collaborator in
// GrokAuthProvider.kt so that class stayed under TooManyFunctions; lifted to its own file so the
// provider is not billed for a second type (concentration HIGH, 2026-08-19).
package splice.provider.grok

import splice.core.auth.AuthDescription
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LoopbackOverride
import splice.core.util.SafeFailureText
import splice.upstream.credentials.TokenRenewal
import java.nio.file.Files
import java.nio.file.Path

private const val REFRESH_ERROR_SNIPPET = 160

public object GrokOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "b1a00492-073a-47ea-816f-4c329264a828"
    public const val REDIRECT_PORT: Int = 56121
    public const val REDIRECT_URI: String = "http://127.0.0.1:56121/callback"
    public const val SCOPE: String = "openid profile email offline_access grok-cli:access api:access"

    public fun issuer(env: EnvReader): String =
        LoopbackOverride.url(env, "GROK_OAUTH_ISSUER", "https://auth.x.ai").trimEnd('/')

    public fun authorizeUrl(env: EnvReader): String =
        LoopbackOverride.url(env, "GROK_OAUTH_AUTHORIZE_URL", "${issuer(env)}/oauth2/authorize")

    // discovery would resolve this, but the CLI's endpoint is stable; env-overridable for safety.
    public fun tokenUrl(env: EnvReader): String =
        LoopbackOverride.url(env, "GROK_OAUTH_TOKEN_URL", "${issuer(env)}/oauth2/token")

    public fun clientId(env: EnvReader): String =
        env("GROK_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID
}

// At file scope, unqualified at its call site, exactly like the codex twin in CodexAuthDescribe.kt —
// nested it forced every caller to spell `GrokAuthFile.PersistRotation` where codex writes
// `PersistRotation` (review 2026-08-28, PR 99).
internal fun interface PersistRotation {
    operator fun invoke(refreshToken: String, fresh: GrokRefreshedTokens, access: String): RefreshOutcome
}

internal class GrokAuthDescribe(
    private val authPath: Path,
    private val authJson: GrokAuthJson,
    private val renewal: TokenRenewal,
    private val refreshCall: RefreshCall<GrokRefreshedTokens>,
) {
    internal fun describe(): AuthDescription {
        val presentOutcome = Cancellables.runCatchingCancellable {
            authJson.tokensOf()?.get("access_token") != null
        }
        // A failed read is not "present"; its failure is consumed below via exceptionOrNull -> read_error.
        val present = presentOutcome.getOrElse { false }
        return AuthDescription(
            present = present,
            kind = "grok-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "browser")
                if (renewal.latch.isLatched(renewal.identity())) put("refresh_latched", INVALID_GRANT_REASON)
                presentOutcome.exceptionOrNull()?.let { failure ->
                    // DR-59: indeterminate is not logged-out — name it in the description instead.
                    val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                        !Files.exists(authPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if (!genuinelyAbsent) put("read_error", SafeFailureText.render(failure))
                }
            },
        )
    }

    internal suspend fun refreshLocked(priorAccess: String?, persist: PersistRotation): RefreshOutcome {
        val (snap, refreshToken) = Cancellables.runCatchingCancellable {
            authJson.parseSnapshot() to JsonScalars.str(authJson.tokensOf()?.get("refresh_token"))
        }.getOrElse { return RefreshOutcome.ReadFailed(it) }
        authJson.peerRotation(priorAccess, snap)?.let { return it }
        return if (refreshToken == null) {
            RefreshOutcome.NoRefreshToken
        } else {
            exchangeRefreshToken(refreshToken, snap?.access, persist)
        }
    }

    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        usedAccess: String?,
        persist: PersistRotation,
    ): RefreshOutcome {
        val attempt = Cancellables.runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrAdopted(refreshToken, usedAccess, "refresh response missing access_token")
                } else {
                    persist(refreshToken, attempt.tokens, access)
                }
            }
            is RefreshAttempt.InvalidGrant -> rejectedOrAdopted(refreshToken, usedAccess, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> rejectedOrAdopted(refreshToken, usedAccess, attempt.detail)
            // No verdict on the token, so no confirming re-read: the turn's engine asks again later.
            is RefreshAttempt.Unavailable -> RefreshOutcome.Unavailable(attempt.detail)
        }
    }

    // G1, the confirming re-read: an endpoint rejection MIGHT be a stale-token race. When the auth file now holds
    // a DIFFERENT refresh token, a peer (the grok CLI, which takes no splice lock) rotated it while our POST was in
    // flight, and its pair is fresh: it is adopted as the current credential and nothing is posted. Posting the
    // peer's refresh token once, as this did until Nov 10, 2026, spent the token the grok CLI had just been issued
    // and could sign the CLI out. The adopted access token is served as it is and renewed later by the usual rules
    // (its proactive window, or a 401 naming that exact token). One POST per renewal, whatever the race.
    private fun rejectedOrAdopted(usedRefreshToken: String, usedAccess: String?, reason: String): RefreshOutcome {
        // When the reread FAILS, the rejection is unconfirmed: surface the read failure in the reason (codex
        // twin parity) — the composite string also keeps the invalid_grant latch from arming on it.
        val reread = Cancellables.runCatchingCancellable {
            authJson.parseSnapshot() to JsonScalars.str(authJson.tokensOf()?.get("refresh_token"))
        }
        val rereadFailure = reread.exceptionOrNull()
        if (rereadFailure != null) {
            val detail = SafeFailureText.render(rereadFailure).take(REFRESH_ERROR_SNIPPET)
            return RefreshOutcome.Rejected("$reason; credential reread failed: $detail")
        }
        val (snap, newToken) = reread.getOrThrow()
        if (newToken == null || newToken == usedRefreshToken) return RefreshOutcome.Rejected(reason)
        return authJson.peerRotation(usedAccess, snap)
            ?: RefreshOutcome.Rejected("$reason; the credential file changed during the renewal")
    }
}
