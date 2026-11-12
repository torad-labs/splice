// NEW: each OAuth kind's on-disk credential shape, so AddCredentialFile dispatches by AuthKind
// instead of decoding chatgpt/grok/kimi/muse layouts inline.
package splice.upstream.credentials

import kotlinx.serialization.json.JsonObject

/** Access / refresh / expiry as that kind stores them. [refreshOptional] is muse: access alone is enough. */
public data class CredentialTokens(
    public val access: String?,
    public val refresh: String?,
    public val expiresAtMs: Long?,
    public val refreshOptional: Boolean = false,
) {
    /** access and refresh are the credential material this type exists to carry. The expiry and the
     *  muse-only [refreshOptional] flag are not secrets, and whether a token is held at all is
     *  diagnostic rather than secret, so absence survives the redaction. */
    override fun toString(): String =
        "CredentialTokens(access=${held(access)}, refresh=${held(refresh)}, " +
            "expiresAtMs=$expiresAtMs, refreshOptional=$refreshOptional)"

    /** `null` or `<redacted>` — never the value. */
    private fun held(value: String?): String = if (value == null) "null" else "<redacted>"
}

/** Read one kind's token material from a parsed auth file. Null = this shape does not judge that file. */
public fun interface CredentialShape {
    public fun material(root: JsonObject): CredentialTokens?

    /** The value a request made OUTSIDE a turn (a model-list probe) presents as its bearer: the
     *  access token for every kind but one. Muse presents an api_key it mints from that token, and
     *  its endpoints refuse the access token itself (HTTP 401 at api.meta.ai/v1/models, 2026-09-22),
     *  so a turn and a probe must read the same field or the probe reports a working login broken. */
    public fun presented(root: JsonObject): String? = material(root)?.access
}
