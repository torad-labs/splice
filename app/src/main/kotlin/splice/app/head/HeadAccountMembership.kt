// NEW: publish current Claude membership off admission while retaining existing selectors, leases and quota owners.
package splice.app.head

import splice.accounts.order.AccountOrderStore
import splice.app.auth.claude.ClaudePoolChange
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.app.head.books.SharedPools
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.head.usage.QuotaTracker
import splice.head.usage.TrackedAccountQuota
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountMembershipRefresh
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class HeadAccountMembership(
    private val ctx: ProviderBuild,
    private val wired: Wired,
    private val pool: AccountPool,
    private val trackers: MutableMap<String, QuotaTracker>,
    private val assembly: ProviderAssembly,
    private val holds: ProviderHoldFiles,
    private val orders: AccountOrderStore,
) : ClaudePoolChange, AccountMembershipRefresh {
    private val lock = ReentrantLock()
    private val keys = CopyOnWriteArraySet<String>().apply { add(ctx.key) }
    private val withdrawn = mutableSetOf<String>()
    private val elapsed = ProcessElapsedNow()
    private var polling: HeadQuotaPolling? = null
    private var sharedPools: SharedPools? = null
    private val isFollower: Boolean get() = assembly.claudeServingHeads(ctx.key).isNotEmpty()

    // A login that joins the running head writes its plan readings where the head's own sign-in does.
    private val ownHistory = trackers.getValue(OWN_SIGN_IN_LABEL).historyFor(OWN_SIGN_IN_LABEL)
    private val fallback = PoolAccount(
        OWN_SIGN_IN_LABEL,
        true,
        wired.auth,
        TrackedAccountQuota(trackers.getValue(OWN_SIGN_IN_LABEL)),
        RateLimitCooldown(elapsed),
    )

    fun bind(polling: HeadQuotaPolling, sharedPools: SharedPools? = null) {
        this.polling = polling
        this.sharedPools = sharedPools
        assembly.claudePoolChanges.bind(ctx.key, this)
        assembly.claudeServingHeads(ctx.key).forEach { source -> assembly.claudePoolChanges.follow(source, ctx.key) }
        pool.membershipRefresh = this
    }

    /** Attaches another route key sharing this pool (e.g. gateway `anthropic` beside `[heads.claude]`), so live
     *  account additions, sign-ins and withdrawals propagate to every attached route's view and change hooks. */
    fun attach(aliasKey: String) {
        lock.withLock {
            if (!keys.add(aliasKey)) return
            assembly.claudePoolChanges.bind(aliasKey, this)
            assembly.claudePoolChanges.accounts(aliasKey, wired.liveAccounts)
        }
    }

    override fun refresh() = update()

    override fun withdraw(label: String) {
        lock.withLock {
            withdrawn.add(label)
            polling?.remove(label)
            if (!isFollower) {
                pool.members.firstOrNull { it.label == label }?.let {
                    sharedPools?.unregisterMember(it.label, it.auth)
                }
                holds.forget(ctx.key, label)
            }
            dropTracker(label)
            pool.members = pool.members.filter { it.label != label }.ifEmpty { listOf(fallback) }
            publishAccounts(wired.liveAccounts.filter { it.label != label })
        }
    }

    override fun publish() = update()

    /** The pool reads its logins again, then lets go of whatever held [label] out for its credential: the person
     *  just signed it in, so the next turn asks the provider with it ([AccountPool.askAgain]). */
    override fun signedIn(label: String) {
        update()
        pool.askAgain(label)
    }

    override fun completeWithdrawal(label: String) {
        lock.withLock {
            withdrawn.remove(label)
            update()
        }
    }

    private fun unchanged(current: List<WiredAccount>): Boolean {
        val previous = wired.liveAccounts.associateBy(WiredAccount::label)
        return current.size == previous.size && current.all { account ->
            val existing = previous[account.label]
            existing != null && account.copy(auth = existing.auth) == existing &&
                (account.nativePlace == null || account.auth === existing.auth)
        }
    }

    private fun update() {
        lock.withLock {
            val current = assembly.claudeAccounts(ctx.key, wired.auth).filterNot { it.label in withdrawn }
            if (unchanged(current)) return
            val previous = pool.members.associateBy(PoolAccount::label)
            forgetNewHolds(current, previous)
            val held = holds.forAccounts(ctx.key, wired.copy(accounts = current))
            val labels = current.map { it.label }.toSet() + OWN_SIGN_IN_LABEL
            trackers.keys.filter { it !in labels }.forEach(::dropTracker)
            val next = current.map { memberFor(it, previous, held) }
            pool.members = next.ifEmpty { listOf(fallback) }
            pool.order = orders.order(ctx.key).filter(pool.members.map(PoolAccount::label).toSet()::contains)
            publishAccounts(current)
            polling?.update(current, trackers)
        }
    }

    private fun forgetNewHolds(current: List<WiredAccount>, previous: Map<String, PoolAccount>) {
        // A label new to a head is a login just filed; a multi-head gateway follower must never forget a head's holds.
        if (isFollower) return
        current.filter { it.nativePlace == null && it.label !in previous }.forEach { holds.forget(ctx.key, it.label) }
    }

    private fun dropTracker(label: String) {
        val removed = trackers.remove(label)
        if (!isFollower) removed?.retire()
    }

    private fun publishAccounts(current: List<WiredAccount>) {
        keys.forEach { key -> assembly.claudePoolChanges.accounts(key, current) }
    }

    private fun memberFor(
        account: WiredAccount,
        previous: Map<String, PoolAccount>,
        held: Map<String, ProviderHoldStore>,
    ): PoolAccount {
        previous[account.label]?.takeIf { it.auth === account.auth }?.let { return it }
        dropTracker(account.label)
        val shareable = account.nativePlace == null && !account.primary
        if (shareable) {
            sharedPools?.reuseMember(ctx.key, pool, account.label, account.auth)?.let { existing ->
                trackers[account.label] = existing.tracker
                return existing.account
            }
        }
        return createMember(account, held, shareable)
    }

    private fun createMember(
        account: WiredAccount,
        held: Map<String, ProviderHoldStore>,
        shareable: Boolean,
    ): PoolAccount {
        val tracker = trackers.computeIfAbsent(account.label) {
            QuotaTracker(
                account.quota.file,
                extraFamily = CodexQuotaHeaderFamily(),
                history = ownHistory?.forLogin(account.label),
            )
        }.also {
            it.credentialListener = account.quota.read as? splice.head.usage.CredentialQuotaListener
        }
        val member = PoolAccount(
            label = account.label,
            primary = account.primary,
            auth = account.auth,
            quota = TrackedAccountQuota(tracker, account.quota.read),
            cooldown = RateLimitCooldown(elapsed, store = held[account.label]),
            credentialPresent = account.credential.present,
            extraHeaders = account.extraHeaders,
        )
        if (shareable) sharedPools?.registerMember(ctx.key, pool, member, tracker)
        return member
    }
}
