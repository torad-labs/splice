// NEW: v0.4.0 FEATURES.md §11 — head-local OAuth account pool assembly and control projection.
package splice.app.head

import splice.app.provider.Wired
import splice.control.HeadAccountAuthSource
import splice.control.HeadAccountPoolSource
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountSwitchView
import splice.control.HeadAccountView
import splice.gateway.usage.QuotaTracker
import splice.spi.AccountNow
import splice.spi.AccountPool
import splice.spi.AccountPoolView
import splice.spi.AccountQuotaSource
import splice.spi.PoolAccount
import splice.spi.ProcessElapsedNow
import splice.spi.RateLimitCooldown

internal class HeadAccountPools {
    private val elapsedNow = ProcessElapsedNow()
    private val accountNow = AccountNow(System::currentTimeMillis)

    /** A pool is a CHOICE. Discovery always yields the primary, so a head holding one account keeps
     *  the pre-0.4.0 path end to end (no per-turn selection, no account segment on the statusline,
     *  no account_pool on /api/auth) and renders byte-identical to a head that never had a pool. */
    fun build(wired: Wired, trackers: Map<String, QuotaTracker>): AccountPool? {
        if (!pooled(wired)) return null
        val accounts = wired.accounts.map { account ->
            val tracker = trackers.getValue(account.label)
            PoolAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quota = AccountQuotaSource { tracker.snapshot() },
                cooldown = RateLimitCooldown(elapsedNow),
                credentialPresent = account.credentialPresent,
                extraHeaders = account.extraHeaders,
            )
        }
        return AccountPool(accounts, accountNow)
    }

    fun source(pool: AccountPool?): HeadAccountPoolSource? = pool?.let { accountPool ->
        HeadAccountPoolSource { sessionId -> controlView(accountPool.view(sessionId)) }
    }

    fun authSource(wired: Wired): HeadAccountAuthSource? = wired.accounts.takeIf { pooled(wired) }
        ?.let { accounts ->
            HeadAccountAuthSource {
                accounts.associate { account -> account.label to account.auth.describe() }
            }
        }

    private fun pooled(wired: Wired): Boolean = wired.accounts.size > 1

    private fun controlView(view: AccountPoolView): HeadAccountPoolView = HeadAccountPoolView(
        selectedLabel = view.selectedLabel,
        accounts = view.accounts.map { account ->
            HeadAccountView(
                label = account.label,
                primary = account.primary,
                selected = account.selected,
                available = account.available,
                plan = account.plan,
                fiveHourUsedPercent = account.fiveHourUsedPercent,
                fiveHourResetEpochSeconds = account.fiveHourResetEpochSeconds,
                sevenDayUsedPercent = account.sevenDayUsedPercent,
                sevenDayResetEpochSeconds = account.sevenDayResetEpochSeconds,
                credentialPresent = account.credentialPresent,
            )
        },
        lastSwitch = view.lastSwitch?.let { switch ->
            HeadAccountSwitchView(switch.from, switch.to, switch.reason, switch.atEpochMillis)
        },
    )
}
