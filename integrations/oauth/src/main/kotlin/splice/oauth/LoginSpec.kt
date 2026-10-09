// NEW: everything the OAuth flow needs for one provider's login.
// Split from OAuthLoginFlow.kt so the orchestrator is not billed for
// the spec DTO (concentration, 2026-08-19). Same-package — callers
// keep splice.app.auth.LoginSpec.
package splice.oauth

import java.nio.file.Path

/** Everything the flow needs for one provider's login (built by LoginCommand per head). */
public data class LoginSpec(
    public val head: String,
    public val authorizeUrl: String, // already built with challenge + state + nonce
    public val callback: LoopbackCallback,
    public val exchange: TokenExchange,
    public val authPath: Path,
    public val account: OAuthLoginAccount? = null,
)
