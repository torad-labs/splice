// NEW: shared live single-login cooldowns so a legacy head and a gateway route over the same login share holds.
package splice.upstream.retry

import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.NativeReply
import java.util.concurrent.TimeUnit

/** Live single-login cooldowns shared across routes that serve the same upstream credential. */
public class UpstreamCooldowns(
    private val clock: ElapsedClock,
    private val wall: WallClock = WallClock(System::currentTimeMillis),
    public val store: ProviderHoldStore? = null,
) {
    internal val cooldown: RateLimitCooldown = RateLimitCooldown(clock, wall)
    internal val credentials: CredentialCooldowns = CredentialCooldowns(clock, store, wall)

    /** True once more than one route shares these cooldowns, so restarting one head does not wipe the other's holds. */
    @Volatile
    public var sharedAcrossRoutes: Boolean = false

    /** Migrates active persisted credential-scoped holds from [other] into [credentials] and [store], deleting each
     *  source file only after [store] confirms the migrated hold persisted. Unscoped legacy files stay untouched. */
    public fun adoptFrom(other: ProviderHoldStore) {
        val destStore = store ?: return
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(wall())
        other.credentialKeys().forEach { key ->
            val scoped = other.forCredential(key) ?: return@forEach
            val hold = scoped.load() ?: return@forEach
            val target = destStore.forCredential(key) ?: return@forEach
            val live = credentials.forKey(key)
            live.adoptHold(hold)
            if (persisted(hold, target.load(), live.rateLimitReply, nowSeconds)) scoped.save(ProviderHold(null, null))
        }
    }

    private fun persisted(
        source: ProviderHold,
        target: ProviderHold?,
        expectedReply: NativeReply?,
        nowSeconds: Long,
    ): Boolean {
        if (!resetPersisted(source, target, nowSeconds)) return false
        if (!replyPersisted(source, target, expectedReply, nowSeconds)) return false
        return planPersisted(source.plan, target?.plan, nowSeconds) &&
            modelsPersisted(source.models, target?.models.orEmpty(), nowSeconds)
    }

    private fun resetPersisted(source: ProviderHold, target: ProviderHold?, nowSeconds: Long): Boolean {
        val wanted = source.providerResetAtEpochSeconds?.takeIf { it > nowSeconds } ?: return true
        return (target?.providerResetAtEpochSeconds ?: 0L) >= wanted
    }

    private fun replyPersisted(
        source: ProviderHold,
        target: ProviderHold?,
        expectedReply: NativeReply?,
        nowSeconds: Long,
    ): Boolean {
        if (source.rateLimitReply == null) return true
        val activeReset = source.providerResetAtEpochSeconds?.let { it > nowSeconds } == true
        val activePlan = source.plan?.let { !it.modelUnknown && it.resetEpochSeconds > nowSeconds } == true
        return (!activeReset && !activePlan) || target?.rateLimitReply == expectedReply
    }

    private fun planPersisted(source: PlanLimit?, target: PlanLimit?, nowSeconds: Long): Boolean {
        val wanted = source?.takeIf { !it.modelUnknown && it.resetEpochSeconds > nowSeconds } ?: return true
        return (target?.resetEpochSeconds ?: 0L) >= wanted.resetEpochSeconds
    }

    private fun modelsPersisted(
        source: List<PlanLimit>,
        target: List<PlanLimit>,
        nowSeconds: Long,
    ): Boolean = source
        .filter { it.forOneModel && it.resetEpochSeconds > nowSeconds }
        .all { wanted -> target.any { it.model == wanted.model && it.resetEpochSeconds >= wanted.resetEpochSeconds } }
}
