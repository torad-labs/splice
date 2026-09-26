// NEW: Muse subscription-key mint boundary and typed provider-local outcomes.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject

/** Why a Muse subscription key is being minted. */
public enum class MuseMintMode {
    ONBOARD,
    REFRESH,
}

/** Exchanges a persisted Meta account token for one Muse inference key. */
public fun interface MuseKeyMintCall {
    public suspend operator fun invoke(
        accessToken: String,
        mode: MuseMintMode,
    ): MuseMintAttempt
}

/** One classified subscription-key exchange result. */
public sealed class MuseMintAttempt {
    public data class Granted(
        public val key: MuseSubscriptionKey,
    ) : MuseMintAttempt()

    public data object InvalidAccountToken : MuseMintAttempt()

    public data class SubscriptionRequired(
        public val actionUrl: String?,
    ) : MuseMintAttempt()

    public data class RateLimited(
        public val retryAfterMs: Long? = null,
    ) : MuseMintAttempt()

    public data class Denied(
        public val detail: String,
    ) : MuseMintAttempt()
}

/** A Muse inference key plus the response fields retained for credential persistence. */
public data class MuseSubscriptionKey(
    public val apiKey: String,
    public val fields: JsonObject,
) {
    /** Public, and both properties are credential material: the minted inference key, and the
     *  response body retained to persist it. Only the field COUNT survives. */
    override fun toString(): String = "MuseSubscriptionKey(apiKey=<redacted>, fields=<redacted:${fields.size} key(s)>)"
}
