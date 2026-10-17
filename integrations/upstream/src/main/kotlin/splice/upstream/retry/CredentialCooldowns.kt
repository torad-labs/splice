// NEW: a head that forwards caller credentials cannot share their refusal or reset.
package splice.upstream.retry

import splice.core.auth.CredentialKey
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import java.util.concurrent.ConcurrentHashMap

/** The effective forwarded credential is the only principal this head can prove. Keys are never logged. */
internal class CredentialCooldowns(
    private val clock: ElapsedClock,
    private val store: ProviderHoldStore?,
) {
    private val cooldowns = ConcurrentHashMap<String, RateLimitCooldown>()

    init {
        store?.credentialKeys()?.forEach { key ->
            cooldowns[key] = RateLimitCooldown(clock, store = store.forCredential(key))
        }
    }

    /** No carriers means no known principal; never manufacture a login from the head or session. */
    public fun forHeaders(headers: Map<String, String>, declaredCarrier: String? = null): RateLimitCooldown? {
        val key = CredentialKey.fromHeaders(headers, declaredCarrier) ?: return null
        return cooldowns.computeIfAbsent(key) { RateLimitCooldown(clock, store = store?.forCredential(key)) }
    }

    public fun clear() {
        cooldowns.values.forEach(RateLimitCooldown::clear)
    }

    /** Reports aggregate pressure, never an admission verdict for an unidentified caller. */
    public val remainingMs: Long get() = cooldowns.values.maxOfOrNull(RateLimitCooldown::remainingMs) ?: 0L

    public val providerResetForMs: Long
        get() = cooldowns.values.maxOfOrNull(RateLimitCooldown::providerUnavailableForMs) ?: 0L

    public val planHold: PlanLimit?
        get() = cooldowns.values.mapNotNull { it.planHold.live() }.maxByOrNull(PlanLimit::resetEpochSeconds)

    public val planHoldForMs: Long
        get() = cooldowns.values.maxOfOrNull { it.planHold.forMs() } ?: 0L
}
