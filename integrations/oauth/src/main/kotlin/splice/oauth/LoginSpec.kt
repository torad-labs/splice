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
    public val redirectPort: Int,
    public val redirectPath: String, // "/auth/callback" (codex) or "/callback" (grok)
    public val expectedState: String,
    public val tokenUrl: String,
    /** Builds the x-www-form-urlencoded exchange body for the real authorization code (encoded here). */
    public val exchangeForm: ExchangeForm,
    public val authPath: Path,
    /** token-endpoint response body → the auth.json content to persist. */
    public val toAuthJson: AuthJsonFromResponse,
    public val account: OAuthLoginAccount? = null,
)
