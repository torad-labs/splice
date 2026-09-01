// NEW: best-effort mtime probe for the invalid_grant latch gate, plus the
// public Codex OAuth endpoint constants (moved from CodexOAuth.kt so that
// class drops below 1.8, 2026-08-19). Shared by doRefresh() and describe().
package splice.provider.codex

import splice.core.auth.CredentialFileIdentity
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

public object CodexOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    public const val REDIRECT_PORT: Int = 1455
    public const val REDIRECT_URI: String = "http://localhost:1455/auth/callback"
    public const val SCOPE: String =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"

    public fun issuer(env: EnvReader): String =
        (env("CODEX_OAUTH_ISSUER") ?: "https://auth.openai.com").trimEnd('/')

    public fun tokenUrl(env: EnvReader): String =
        env("CODEX_OAUTH_TOKEN_URL") ?: "${issuer(env)}/oauth/token"

    public fun authorizeUrl(env: EnvReader): String =
        env("CODEX_OAUTH_AUTHORIZE_URL") ?: "${issuer(env)}/oauth/authorize"

    public fun clientId(env: EnvReader): String =
        env("CODEX_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID

    public fun originator(env: EnvReader): String =
        env("CODEX_OAUTH_ORIGINATOR") ?: "codex_cli_rs"
}

internal class CodexAuthFile {
    // DR-176: returns the file IDENTITY, not a bare mtime. Truncated milliseconds could not tell a
    // freshly re-authenticated credential from the rejected one it replaced within the same tick.
    fun codexAuthIdentityOrNull(authPath: Path, log: LogSink): CredentialFileIdentity? =
        Cancellables.runCatchingCancellable {
            CredentialFileIdentity(Files.getLastModifiedTime(authPath).toMillis(), Files.size(authPath))
        }.onFailure {
            log(
                "[codex-auth] failed to stat $authPath identity: ${SafeFailureText.render(it)} — " +
                    "invalid_grant latch check skipped",
            )
        }.getOrNull()
}
