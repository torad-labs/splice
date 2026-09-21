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
}
