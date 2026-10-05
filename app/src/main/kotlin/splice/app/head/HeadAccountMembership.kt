// NEW: publish current Claude membership off admission while retaining existing selectors, leases and quota owners.
package splice.app.head

import splice.accounts.order.AccountOrderStore
import splice.app.auth.claude.ClaudePoolChange
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.head.usage.QuotaTracker
import splice.head.usage.TrackedAccountQuota
import splice.provider.codex.CodexQuotaHeaderFamily
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
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
) : ClaudePoolChange {
    private val lock = ReentrantLock()
    private val elapsed = ProcessElapsedNow()
    private var polling: HeadQuotaPolling? = null
    private val fallback = PoolAccount(
        OWN_SIGN_IN_LABEL,
        true,
        wired.auth,
        TrackedAccountQuota(trackers.getValue(OWN_SIGN_IN_LABEL)),
        RateLimitCooldown(elapsed),
    )

    fun bind(polling: HeadQuotaPolling) {
        this.polling = polling
        assembly.claudePoolChanges.bind(ctx.key, this)
    }

    override fun withdraw(label: String) {
        lock.withLock {
            polling?.remove(label)
            trackers.remove(label)?.retire()
            pool.members = pool.members.filter { it.label != label }
            assembly.claudePoolChanges.accounts(ctx.key, wired.liveAccounts.filter { it.label != label })
        }
    }

    override fun publish() {
        lock.withLock {
            val current = assembly.claudeAccounts(ctx.key, wired.auth)
            val previous = pool.members.associateBy(PoolAccount::label)
            val held = holds.forAccounts(ctx.key, wired.copy(accounts = current))
            val labels = current.map { it.label }.toSet() + OWN_SIGN_IN_LABEL
            trackers.keys.filter { it !in labels }.forEach { trackers.remove(it)?.retire() }
            val next = current.map { account ->
                val retained = previous[account.label]?.takeIf {
                    account.nativePlace == null || it.auth === account.auth
                }
                if (retained == null) trackers.remove(account.label)?.retire()
                retained ?: PoolAccount(
                    label = account.label,
                    primary = account.primary,
                    auth = account.auth,
                    quota = TrackedAccountQuota(
                        trackers.computeIfAbsent(account.label) {
                            QuotaTracker(account.quotaFile, extraFamily = CodexQuotaHeaderFamily())
                        }.also {
                            it.credentialListener = account.quotaRead as? splice.head.usage.CredentialQuotaListener
                        },
                        account.quotaRead,
                    ),
                    cooldown = RateLimitCooldown(elapsed, store = held[account.label]),
                    credentialPresent = account.credentialPresent,
                    extraHeaders = account.extraHeaders,
                )
            }
            pool.members = next.ifEmpty { listOf(fallback) }
            pool.order = orders.order(ctx.key).filter(pool.members.map(PoolAccount::label).toSet()::contains)
            assembly.claudePoolChanges.accounts(ctx.key, current)
            polling?.update(current, trackers)
        }
    }
}
