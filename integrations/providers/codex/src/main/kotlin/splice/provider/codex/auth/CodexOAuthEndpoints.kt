// NEW: the public Codex OAuth endpoint constants (moved from CodexOAuth.kt so that class drops below 1.8,
// 2026-08-19). The latch's file-identity probe that lived here moved to the shared TokenRenewal (audit, Nov 9, 2026).
package splice.provider.codex.auth

import splice.core.util.EnvReader
import splice.core.util.LoopbackOverride

public object CodexOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    public const val REDIRECT_PORT: Int = 1455
    public const val REDIRECT_URI: String = "http://localhost:1455/auth/callback"
    public const val SCOPE: String =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"

    public fun issuer(env: EnvReader): String =
        LoopbackOverride.url(env, "CODEX_OAUTH_ISSUER", "https://auth.openai.com").trimEnd('/')

    public fun tokenUrl(env: EnvReader): String =
        LoopbackOverride.url(env, "CODEX_OAUTH_TOKEN_URL", "${issuer(env)}/oauth/token")

    public fun authorizeUrl(env: EnvReader): String =
        LoopbackOverride.url(env, "CODEX_OAUTH_AUTHORIZE_URL", "${issuer(env)}/oauth/authorize")

    public fun clientId(env: EnvReader): String =
        env("CODEX_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID

    public fun originator(env: EnvReader): String =
        env("CODEX_OAUTH_ORIGINATOR") ?: "codex_cli_rs"
}
