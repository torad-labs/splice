// NEW: parsed result of the grok token-endpoint refresh POST. Split from GrokAuthProvider.kt
// so the refresh ladder is not billed for a field group (concentration HIGH, 2026-08-19).
package splice.provider.grok

/** Parsed result of the grok token-endpoint refresh POST. */
public data class GrokRefreshedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    /** Seconds until the new access token expires; null when the endpoint omits it. */
    val expiresIn: Long? = null,
) {
    /** Both tokens are secrets; the expiry is not, and neither is whether a token came back at all. */
    override fun toString(): String =
        "GrokRefreshedTokens(accessToken=${held(accessToken)}, refreshToken=${held(refreshToken)}, " +
            "expiresIn=$expiresIn)"

    /** `null` or `<redacted>` — never the value. */
    private fun held(value: String?): String = if (value == null) "null" else "<redacted>"
}
