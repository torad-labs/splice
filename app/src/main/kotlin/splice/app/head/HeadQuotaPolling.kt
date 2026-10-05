// NEW: quota polling follows live membership, borrowing the daemon's existing poller creation and lifetime.
package splice.app.head

import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthProvider
import splice.core.auth.ClientAuthProvider
import splice.core.config.Knob
import splice.head.usage.QuotaTracker
import splice.provider.muse.MuseAuthProvider
import splice.usage.quota.ClientUserAgent
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
) {
    private val held = ConcurrentHashMap<String, QuotaPoller>()
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
                live.add(it)
            }
        } else {
            update(wired.accounts, stores.accountQuotas)
        }
        if (wired.auth is ClientAuthProvider) {
            stores.accountPool?.let { pool ->
                trackers.putIfAbsent(pool.members.single { it.primary }.label, stores.quota)
                HeadAccountMembership(ctx, wired, pool, trackers, assembly, holds).bind(this)
            }
        }
        return pollers
    }

    fun remove(label: String) {
        held.remove(label)?.let {
            it.stop()
            live.remove(it)
        }
    }

    fun update(accounts: List<WiredAccount>, trackers: Map<String, QuotaTracker>) {
        val labels = accounts.map(WiredAccount::label).toSet()
        held.keys.filter { it !in labels }.forEach(::remove)
        accounts.filter { !held.containsKey(it.label) }.forEach { account ->
            startAccount(account.auth, trackers.getValue(account.label))?.let {
                held[account.label] = it
                live.add(it)
            }
        }
    }

    private fun startAccount(auth: AuthProvider, tracker: QuotaTracker): QuotaPoller? {
        if (ctx.cfg.quotaPollOff) return null
        // V4-110: the merged cadence knob is always seeded and already floored by ConfigCoercion.
        val intervalMs = ctx.cfg.asMap()[Knob.QUOTA_POLL_INTERVAL_MS.key] as Long
        val usageFields = (auth as? MuseAuthProvider)?.let { muse -> UsageFields { muse.usageFields() } }
        val probe = probes.forHead(
            ctx.providerCfg.auth.kind,
            ctx.providerCfg.baseUrl,
            auth,
            usageFields,
            clientUserAgent,
        ) ?: return null
        return startQuotaPoller(ctx.key, probe, tracker, intervalMs)
    }
}
