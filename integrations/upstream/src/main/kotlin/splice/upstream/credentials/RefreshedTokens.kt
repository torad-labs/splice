// NEW: result of the token endpoint's refresh POST (only the fields we persist). Moved from
// :providers-codex so the daemon-wide TokenUrlRefreshCall is not typed on one vendor.
package splice.upstream.credentials

/** Result of the token endpoint's refresh POST (only the fields we persist). */
public data class RefreshedTokens(
    public val accessToken: String?,
    public val refreshToken: String?,
    public val idToken: String?,
) {
    /** All three are secrets. PRESENCE is not: whether a refresh came back with a token at all is
     *  the thing worth reading in a log, and it is the only thing kept. */
    override fun toString(): String =
        "RefreshedTokens(accessToken=${held(accessToken)}, refreshToken=${held(refreshToken)}, " +
            "idToken=${held(idToken)})"

    /** `null` or `<redacted>` — never the value. */
    private fun held(value: String?): String = if (value == null) "null" else "<redacted>"
}
