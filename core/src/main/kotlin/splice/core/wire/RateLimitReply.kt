// NEW: the provider's native 429 answer, retained without translating its limit headers.
package splice.core.wire

import kotlinx.serialization.Serializable

/** Only the rate-limit response family is retained; credential and unrelated headers never enter it. */
@Serializable
public data class RateLimitReply(
    public val body: String,
    public val headers: Map<String, List<String>>,
) {
    public val status: Int get() = HttpStatus.TOO_MANY_REQUESTS
}
