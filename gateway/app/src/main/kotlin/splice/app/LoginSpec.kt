// NEW: everything the OAuth flow needs for one provider's login.
// Split from OAuthLoginFlow.kt so the orchestrator is not billed for
// the spec DTO (concentration, 2026-08-19). Same-package — callers
// keep splice.app.LoginSpec.
package splice.app

import java.nio.file.Path

/** Everything the flow needs for one provider's login (built by LoginCommand per head). */
public data class LoginSpec(
    val head: String,
    val authorizeUrl: String, // already built with challenge + state + nonce
    val redirectPort: Int,
    val redirectPath: String, // "/auth/callback" (codex) or "/callback" (grok)
    val expectedState: String,
    val tokenUrl: String,
    /** Builds the x-www-form-urlencoded exchange body for the real authorization code (encoded here). */
    val exchangeForm: ExchangeForm,
    val authPath: Path,
    /** token-endpoint response body → the auth.json content to persist. */
    val toAuthJson: AuthJsonFromResponse,
)
