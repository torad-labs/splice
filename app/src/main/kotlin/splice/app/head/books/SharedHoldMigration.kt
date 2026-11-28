// NEW: upgrade hold migration when a secondary route (e.g. gateway `anthropic` or `codex`) attaches to an existing
// shared pool owned by a legacy head (e.g. `claude` or `claudex`), so active provider or login holds recorded under
// the secondary key are adopted live and persisted to the canonical owner before removing the old secondary files.
package splice.app.head.books

import splice.app.head.ProviderHoldFiles
import splice.core.usage.PlanLimit
import splice.core.util.WallClock
import splice.core.wire.NativeReply
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.ProviderHold
import splice.upstream.retry.SavedLoginHold
import splice.upstream.retry.UpstreamCooldowns
import java.util.concurrent.TimeUnit

internal const val NATIVE_LABEL_PREFIX = "native:"

internal class SharedHoldMigration(
    private val holds: ProviderHoldFiles,
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** Builds the live single-login cooldown holder backed by [key]'s canonical head hold store. */
    fun cooldownsFor(key: String): UpstreamCooldowns = UpstreamCooldowns(
        ProcessElapsedNow(),
        wallClock,
        holds.forHead(key),
    )

    /** Merges any active persisted holds stored under [fromKey] into the live [pool] and [cooldowns] owned by [toKey],
     *  removing each superseded [fromKey] file only after [toKey]'s store confirms the migrated hold persisted. */
    fun migrate(fromKey: String, toKey: String, pool: AccountPool?, cooldowns: UpstreamCooldowns) {
        if (fromKey == toKey) return
        cooldowns.adoptFrom(holds.forHead(fromKey))
        val activePool = pool ?: return
        val now = wallClock()
        activePool.members.filterNot { it.label.startsWith(NATIVE_LABEL_PREFIX) }.forEach { member ->
            migrateAccount(fromKey, toKey, member, now)
        }
    }

    /** Migrates any active persisted holds stored under [fromKey] for one shared [member] owned by [toKey]. */
    fun migrateMember(fromKey: String, toKey: String, member: PoolAccount) {
        if (fromKey == toKey || member.label.startsWith(NATIVE_LABEL_PREFIX)) return
        migrateAccount(fromKey, toKey, member, wallClock())
    }

    private fun migrateAccount(fromKey: String, toKey: String, member: PoolAccount, nowMs: Long) {
        val oldStore = holds.forAddedAccount(fromKey, member.label)
        val targetStore = holds.forAddedAccount(toKey, member.label)
        oldStore.load()?.let { hold ->
            member.cooldown.adoptHold(hold)
            val nowSec = TimeUnit.MILLISECONDS.toSeconds(nowMs)
            if (providerHoldPersisted(hold, targetStore.load(), member.cooldown.rateLimitReply, nowSec)) {
                oldStore.save(ProviderHold(null, null))
            }
        }
        val oldLoginStore = oldStore.loginHold()
        val targetLoginStore = targetStore.loginHold()
        oldLoginStore.load()?.let { saved ->
            val active = saved.untilEpochMillis > nowMs || saved.block != null
            if (!active) {
                oldLoginStore.save(null)
            } else {
                member.adoptLoginHold(saved, nowMs)
                if (loginHoldPersisted(saved, targetLoginStore.load())) oldLoginStore.save(null)
            }
        }
    }

    private fun providerHoldPersisted(
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

    private fun loginHoldPersisted(source: SavedLoginHold, target: SavedLoginHold?): Boolean {
        if (target == null) return false
        val blockOk = source.block == null || target.block != null
        return blockOk && target.untilEpochMillis >= source.untilEpochMillis
    }
}
