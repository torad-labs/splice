// NEW: the token-endpoint half of a browser login, split out of LoginSpec so the spec stays within
// the constructor-width wall. Same package, so callers reach it unqualified.
package splice.oauth

/** The authorization-code exchange: where it posts, the body it sends, and what a success persists. */
public data class TokenExchange(
    public val url: String,
    /** Builds the x-www-form-urlencoded exchange body for the real authorization code (encoded here). */
    public val form: ExchangeForm,
    /** token-endpoint response body → the auth.json content to persist. */
    public val toAuthJson: AuthJsonFromResponse,
)
