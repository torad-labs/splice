// NEW: quota polling follows live membership, borrowing the daemon's existing poller creation and lifetime.
package splice.app.head

import splice.accounts.order.AccountOrderStore
import splice.app.auth.claude.ClaudeNativeQuota
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthProvider
import splice.core.auth.ClientAuthProvider
import splice.head.usage.QuotaTracker
import splice.provider.muse.MuseAuthProvider
import splice.usage.quota.ClientUserAgent
import splice.usage.quota.QuotaIntervalMs
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbes
import splice.usage.quota.UsageFields
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class HeadQuotaPolling(
    private val ctx: ProviderBuild,
    private val probes: QuotaProbes,
    private val startQuotaPoller: StartQuotaPoller,
    private val clientUserAgent: ClientUserAgent,
    private val orders: AccountOrderStore,
) {
    private val held = ConcurrentHashMap<String, QuotaPoller>()
    private val owners = ConcurrentHashMap<String, AuthProvider>()
    private val live = CopyOnWriteArrayList<QuotaPoller>()
    val pollers: List<QuotaPoller> get() = live

    fun start(
        wired: Wired,
        stores: HeadStores,
        trackers: MutableMap<String, QuotaTracker>,
        assembly: ProviderAssembly,
        holds: ProviderHoldFiles,
    ): List<QuotaPoller> {
        if (wired.accounts.isEmpty()) {
            startAccount(wired.auth, stores.quota)?.let {
                held["primary"] = it
                owners["primary"] = wired.auth
                live.add(it)
            }
        } else {
            update(wired.accounts, stores.accounts.quotas)
        }
        if (wired.auth is ClientAuthProvider) {
            stores.accounts.pool?.let { pool ->
                trackers.putIfAbsent(OWN_SIGN_IN_LABEL, stores.quota)
                HeadAccountMembership(ctx, wired, pool, trackers, assembly, holds, orders).bind(this)
            }
        }
        return pollers
    }

    fun remove(label: String) {
        owners.remove(label)
        held.remove(label)?.let {
            it.stop()
            live.remove(it)
        }
    }

    fun update(accounts: List<WiredAccount>, trackers: Map<String, QuotaTracker>) {
        val labels = accounts.map(WiredAccount::label).toSet()
        held.keys.filter { it !in labels }.forEach(::remove)
        accounts.filter { it.nativePlace != null && owners[it.label] !== it.auth }.forEach { remove(it.label) }
        accounts.filter { !held.containsKey(it.label) }.forEach { account ->
            val nativeQuota = account.quota.read as? ClaudeNativeQuota
            startAccount(account.auth, trackers.getValue(account.label), nativeQuota)?.let {
                held[account.label] = it
                owners[account.label] = account.auth
                live.add(it)
            }
        }
    }

    private fun startAccount(
        auth: AuthProvider,
        tracker: QuotaTracker,
        native: ClaudeNativeQuota? = null,
    ): QuotaPoller? {
        if (ctx.cfg.quotaPollOff) return null
        // Read at every wait, off this head's own config as it stands then: an operator who slows a provider's
        // polling is obeyed by the next wait rather than the next restart. ConfigCoercion floors it at 30 s.
        val intervalMs = QuotaIntervalMs { ctx.cfg.current().quotaPollIntervalMs }
        val usageFields = (auth as? MuseAuthProvider)?.let { muse -> UsageFields { muse.usageFields() } }
        val probe = native?.probe(probes, ctx.providerCfg.auth.kind, ctx.providerCfg.baseUrl, clientUserAgent)
            ?: probes.forHead(
                ctx.providerCfg.auth.kind,
                ctx.providerCfg.baseUrl,
                auth,
                usageFields,
                clientUserAgent,
            ) ?: return null
        return startQuotaPoller(ctx.key, probe, tracker, intervalMs)
    }
}
