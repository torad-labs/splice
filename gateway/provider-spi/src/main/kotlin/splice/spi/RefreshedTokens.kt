// NEW: result of the token endpoint's refresh POST (only the fields we persist). Moved from
// provider-codex so the daemon-wide TokenUrlRefreshCall is not typed on one vendor.
package splice.spi

/** Result of the token endpoint's refresh POST (only the fields we persist). */
public data class RefreshedTokens(
    public val accessToken: String?,
    public val refreshToken: String?,
    public val idToken: String?,
)
