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
) {
    /** The browser's part of this login: what it opens and where the code comes back. */
    public val browser: BrowserSignIn get() = BrowserSignIn(head, authorizeUrl, callback)
}

/** A browser sign-in's own part, before any code is traded: the [head] it is for (named on the confirmation page),
 *  the [authorizeUrl] it opens, and the loopback [callback] the provider sends the code back to. */
public data class BrowserSignIn(
    public val head: String,
    public val authorizeUrl: String,
    public val callback: LoopbackCallback,
)

/** Builds a browser sign-in for the loopback port its listener took. */
public fun interface BrowserSignInAt {
    public operator fun invoke(port: Int): BrowserSignIn
}

/** The local redirect endpoint of an OAuth login: where the listener binds and which state it accepts. */
public data class LoopbackCallback(
    public val port: Int,
    public val path: String, // "/auth/callback" (codex) or "/callback" (grok)
    public val expectedState: String,
)

/** The authorization-code exchange: where it posts, the body it sends, and what a success persists. */
public data class TokenExchange(
    public val url: String,
    /** Builds the x-www-form-urlencoded exchange body for the real authorization code (encoded here). */
    public val form: ExchangeForm,
    /** token-endpoint response body → the auth.json content to persist. */
    public val toAuthJson: AuthJsonFromResponse,
)
