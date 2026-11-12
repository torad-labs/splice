// NEW: the provider's native 429 answer, retained without translating its limit headers. Named NativeReply in
// failure-handling slice 1 (2026-10-26), since the carriers that hold it beside a failure's cause call it the native
// reply; until the hold lands it is still only the rate-limit family, relayed whole.
package splice.core.wire

import kotlinx.serialization.Serializable

/** Only the rate-limit response family is retained; credential and unrelated headers never enter it. */
@Serializable
public data class NativeReply(
    public val body: String,
    public val headers: Map<String, List<String>>,
) {
    public val status: Int get() = HttpStatus.TOO_MANY_REQUESTS
}
