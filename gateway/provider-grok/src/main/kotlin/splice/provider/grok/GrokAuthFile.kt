// NEW: best-effort mtime probe for the invalid_grant latch gate. Was a file-private
// collaborator in GrokAuthProvider.kt so that class stayed under TooManyFunctions;
// lifted to its own file so the provider is not billed for a second type
// (concentration HIGH, 2026-08-19). Shared by doRefresh() and describe().
package splice.provider.grok

import splice.core.auth.AuthDescription
import splice.core.auth.INVALID_GRANT_REASON
import splice.core.auth.InvalidGrantLatch
import splice.core.auth.RefreshAttempt
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshOutcome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

public object GrokOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "b1a00492-073a-47ea-816f-4c329264a828"
    public const val REDIRECT_PORT: Int = 56121
    public const val REDIRECT_URI: String = "http://127.0.0.1:56121/callback"
    public const val SCOPE: String = "openid profile email offline_access grok-cli:access api:access"

    public fun issuer(env: EnvReader): String =
        (env("GROK_OAUTH_ISSUER") ?: "https://auth.x.ai").trimEnd('/')

    public fun authorizeUrl(env: EnvReader): String =
        env("GROK_OAUTH_AUTHORIZE_URL") ?: "${issuer(env)}/oauth2/authorize"

    // discovery would resolve this, but the CLI's endpoint is stable; env-overridable for safety.
    public fun tokenUrl(env: EnvReader): String =
        env("GROK_OAUTH_TOKEN_URL") ?: "${issuer(env)}/oauth2/token"

    public fun clientId(env: EnvReader): String =
        env("GROK_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID
}

internal class GrokAuthFile(
    private val authPath: Path,
    private val authJson: GrokAuthJson,
    private val invalidGrantLatch: InvalidGrantLatch,
    private val log: LogSink,
    private val refreshCall: RefreshCall<GrokRefreshedTokens>,
) {
    internal fun interface PersistRotation {
        operator fun invoke(refreshToken: String, fresh: GrokRefreshedTokens, access: String): RefreshOutcome
    }

    fun grokAuthMtimeOrNull(authPath: Path, log: LogSink): Long? = Cancellables.runCatchingCancellable {
        Files.getLastModifiedTime(authPath).toMillis()
    }.onFailure {
        log("[grok-auth] failed to stat $authPath mtime: $it — invalid_grant latch check skipped")
    }.getOrNull()

    internal fun describe(): AuthDescription {
        // ast-grep-ignore: kt-no-silent-result-collapse -- introspection display only: a read failure renders as present=false, which is the displayed truth
        val present = Cancellables.runCatchingCancellable {
            Files.exists(authPath) && authJson.tokensOf()?.get("access_token") != null
        }.getOrDefault(false)
        val mtime = grokAuthMtimeOrNull(authPath, log)
        return AuthDescription(
            present = present,
            kind = "grok-oauth",
            fields = buildMap {
                put("auth_path", authPath.toString())
                put("login", "browser")
                if (invalidGrantLatch.isLatched(mtime)) put("refresh_latched", INVALID_GRANT_REASON)
            },
        )
    }

    internal suspend fun refreshLocked(priorAccess: String?, persist: PersistRotation): RefreshOutcome {
        val (snap, refreshToken) = Cancellables.runCatchingCancellable {
            authJson.parseSnapshot() to JsonScalars.str(authJson.tokensOf()?.get("refresh_token"))
        }.getOrElse { return RefreshOutcome.ReadFailed(it) }
        authJson.peerRotation(priorAccess, snap)?.let { return it }
        return if (refreshToken == null) RefreshOutcome.NoRefreshToken else exchangeRefreshToken(refreshToken, persist)
    }

    private suspend fun exchangeRefreshToken(
        refreshToken: String,
        persist: PersistRotation,
        allowRereadRetry: Boolean = true,
    ): RefreshOutcome {
        val attempt = Cancellables.runCatchingCancellable { refreshCall(refreshToken) }
            .getOrElse { return RefreshOutcome.TransportFailed(it) }
        return when (attempt) {
            is RefreshAttempt.Granted -> {
                val access = attempt.tokens.accessToken
                if (access == null) {
                    rejectedOrRetry(refreshToken, persist, allowRereadRetry, "refresh response missing access_token")
                } else {
                    persist(refreshToken, attempt.tokens, access)
                }
            }
            is RefreshAttempt.InvalidGrant -> rejectedOrRetry(refreshToken, persist, allowRereadRetry, INVALID_GRANT_REASON)
            is RefreshAttempt.Denied -> rejectedOrRetry(refreshToken, persist, allowRereadRetry, attempt.detail)
        }
    }

    private suspend fun rejectedOrRetry(
        usedRefreshToken: String,
        persist: PersistRotation,
        allowRereadRetry: Boolean,
        reason: String,
    ): RefreshOutcome {
        if (!allowRereadRetry) return RefreshOutcome.Rejected(reason)
        val newToken = Cancellables.runCatchingCancellable { JsonScalars.str(authJson.tokensOf()?.get("refresh_token")) }
            .getOrElse { return RefreshOutcome.Rejected(reason) }
        return if (newToken != null && newToken != usedRefreshToken) {
            exchangeRefreshToken(newToken, persist, allowRereadRetry = false)
        } else {
            RefreshOutcome.Rejected(reason)
        }
    }
}
