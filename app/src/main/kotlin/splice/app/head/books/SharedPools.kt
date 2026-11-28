// NEW: shared live pool, quota trackers, pollers and single-login cooldowns across routes serving the same provider
// and credential owners (e.g. `[heads.claude]` + gateway `anthropic`, or `[heads.claudex]` + gateway `codex`).
package splice.app.head.books

import splice.app.auth.PRIMARY_LABEL
import splice.app.head.HeadQuotaPolling
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKindRegistry
import splice.head.usage.QuotaTracker
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.UpstreamCooldowns
import splice.usage.quota.QuotaPoller
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class SharedLiveMember(
    val ownerKey: String,
    val ownerPool: AccountPool,
    val account: PoolAccount,
    val tracker: QuotaTracker,
)

internal data class SharedPool(
    val ownerKey: String,
    val pool: AccountPool?,
    val primaryQuota: QuotaTracker,
    val accountQuotas: MutableMap<String, QuotaTracker>,
    val pollers: List<QuotaPoller>,
    val polling: HeadQuotaPolling,
    val cooldowns: UpstreamCooldowns,
)

internal sealed class SharedPoolKey {
    data class Client(val provider: String, val canonicalKey: String) : SharedPoolKey()
    data class OAuth(val provider: String, val accounts: Set<SharedAccountIdentity>) : SharedPoolKey()
}

/** Identity of one wired login by label and shared credential owner reference ([LoginOwners]). */
internal data class SharedAccountIdentity(
    val label: String,
    val auth: RefreshableAuthProvider,
) {
    override fun equals(other: Any?): Boolean =
        other is SharedAccountIdentity && label == other.label && auth === other.auth

    override fun hashCode(): Int = java.util.Objects.hash(label, System.identityHashCode(auth))
}

internal class SharedPools(private val migration: SharedHoldMigration) {
    @PublishedApi
    internal val pools: ConcurrentHashMap<SharedPoolKey, SharedPool> = ConcurrentHashMap()
    private val routeBindings: ConcurrentHashMap<UpstreamCooldowns, AtomicInteger> = ConcurrentHashMap()
    private val policyOwners: ConcurrentHashMap<AccountPool, String> = ConcurrentHashMap()
    private val liveMembers: ConcurrentHashMap<SharedAccountIdentity, SharedLiveMember> = ConcurrentHashMap()

    fun cooldownsFor(key: String): UpstreamCooldowns = migration.cooldownsFor(key)

    /** Returns the sharing key for [ctx]/[wired], or null for routes that do not statically share a pool across keys
     *  (including a gateway client-auth route when two or more declared heads serve that provider). */
    fun keyFor(ctx: ProviderBuild, wired: Wired, claudeCanonicalKey: String?): SharedPoolKey? {
        if (wired.auth is ClientAuthProvider) {
            return claudeCanonicalKey?.let { SharedPoolKey.Client(ctx.head.provider, it) }
        }
        val isOAuth = AuthKindRegistry.from(ctx.providerCfg.auth.kind)?.isOAuth == true
        if (!isOAuth && wired.accounts.isEmpty()) return null
        val identities = wired.accounts
            .mapTo(HashSet()) { SharedAccountIdentity(it.label, it.auth) }
            .ifEmpty { setOf(SharedAccountIdentity(PRIMARY_LABEL, wired.auth)) }
        return SharedPoolKey.OAuth(ctx.head.provider, identities)
    }

    inline fun resolve(key: SharedPoolKey, crossinline build: () -> SharedPool): Pair<SharedPool, Boolean> {
        var created = false
        val shared = pools.computeIfAbsent(key) {
            created = true
            build()
        }
        return shared to created
    }

    /** Attaches a secondary route key to an already-built [shared] pool, propagating live membership and migrating
     *  any persisted holds left under [key] from prior releases. */
    fun attach(shared: SharedPool, key: String) {
        if (key == shared.ownerKey) return
        shared.pool?.sharedAcrossRoutes = true
        shared.cooldowns.sharedAcrossRoutes = true
        shared.polling.membership?.attach(key)
        migration.migrate(fromKey = key, toKey = shared.ownerKey, pool = shared.pool, cooldowns = shared.cooldowns)
    }

    /** Records one assembled head or gateway route bound to [books]. Once more than one route binds (including a
     *  same-key legacy head + gateway route), marks its live pool and cooldowns shared so head restarts do not wipe
     *  live state used by the sibling route. */
    fun bindRoute(books: ProviderBooks) {
        val cooldowns = books.stores.accounts.upstreamCooldowns ?: return
        if (routeBindings.computeIfAbsent(cooldowns) { AtomicInteger(0) }.incrementAndGet() > 1) {
            books.stores.accounts.pool?.sharedAcrossRoutes = true
            cooldowns.sharedAcrossRoutes = true
        }
    }

    /** Claims policy ownership (saved order/pins) for [pool]: the first head to claim owns and restores its saved
     *  policy (`true`); any subsequent head sharing [pool] receives the owner's key and `false` so it never overwrites
     *  the owner's saved order or pins. */
    fun claimPolicyOwner(pool: AccountPool?, key: String): Pair<String, Boolean> {
        if (pool == null) return key to true
        val existing = policyOwners.putIfAbsent(pool, key)
        return if (existing == null) key to true else existing to false
    }

    /** Registers a live added Claude [PoolAccount] and [QuotaTracker] owned by [ownerKey]/[ownerPool]. */
    fun registerMember(ownerKey: String, ownerPool: AccountPool, account: PoolAccount, tracker: QuotaTracker) {
        liveMembers[SharedAccountIdentity(account.label, account.auth)] =
            SharedLiveMember(ownerKey, ownerPool, account, tracker)
    }

    /** Reuses an existing live member across routes (e.g. a multi-head gateway follower reusing a head's member),
     *  marking both pools shared for restart isolation and migrating any persisted holds from [requesterKey]. */
    fun reuseMember(
        requesterKey: String,
        requesterPool: AccountPool,
        label: String,
        auth: RefreshableAuthProvider,
    ): SharedLiveMember? {
        val existing = liveMembers[SharedAccountIdentity(label, auth)] ?: return null
        if (existing.ownerPool !== requesterPool) {
            existing.ownerPool.sharedAcrossRoutes = true
            requesterPool.sharedAcrossRoutes = true
        }
        if (requesterKey != existing.ownerKey) {
            migration.migrateMember(fromKey = requesterKey, toKey = existing.ownerKey, member = existing.account)
        }
        return existing
    }

    fun unregisterMember(label: String, auth: RefreshableAuthProvider) {
        liveMembers.remove(SharedAccountIdentity(label, auth))
    }

    /** Reuses already-registered live added-account members on [pool] or registers newly constructed ones. */
    fun syncInitialMembers(
        ownerKey: String,
        wired: Wired,
        pool: AccountPool?,
        trackers: MutableMap<String, QuotaTracker>,
    ) {
        if (wired.auth !is ClientAuthProvider || pool == null) return
        var replaced = false
        val synced = pool.members.map { member ->
            if (member.primary || member.label.startsWith(NATIVE_LABEL_PREFIX)) {
                member
            } else {
                val existing = reuseMember(ownerKey, pool, member.label, member.auth)
                if (existing != null) {
                    replaced = true
                    trackers[member.label] = existing.tracker
                    existing.account
                } else {
                    trackers[member.label]?.let { registerMember(ownerKey, pool, member, it) }
                    member
                }
            }
        }
        if (replaced) pool.members = synced
    }
}
