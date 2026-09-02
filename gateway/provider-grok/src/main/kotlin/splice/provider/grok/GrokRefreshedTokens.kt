// NEW: parsed result of the grok token-endpoint refresh POST. Split from GrokAuthProvider.kt
// so the refresh ladder is not billed for a field group (concentration HIGH, 2026-08-19).
package splice.provider.grok

/** Parsed result of the grok token-endpoint refresh POST. */
public data class GrokRefreshedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    /** Seconds until the new access token expires; null when the endpoint omits it. */
    val expiresIn: Long? = null,
)
