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
)

/** Read one kind's token material from a parsed auth file. Null = this shape does not judge that file. */
public fun interface CredentialShape {
    public fun material(root: JsonObject): CredentialTokens?
}
