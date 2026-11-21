// NEW: custom-provider (issue #680, cp-diagnostics). The last error each provider answered a turn with, so a person
// whose team provider does not work sees why without reading logs: `splice claude` names it beside the provider's
// heads, and `splice doctor` under the provider. Kept per provider, in memory, until a newer error replaces it, so it
// outlives the retry that worked. The message is the provider's own words as a line would quote them, never a
// credential.
package splice.core.failure

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One error a provider answered: when, with what status, and its words. */
public data class ProviderError(val at: Instant, val status: Int, val message: String)

public object ProviderErrors {
    private val last = ConcurrentHashMap<String, ProviderError>()

    /** Provider [key] answered [error]; it replaces the one before. */
    public fun record(key: String, error: ProviderError) {
        last[key] = error
    }

    /** The last error provider [key] answered, or null when it answered none since splice started. */
    public fun of(key: String): ProviderError? = last[key]
}
