// NEW: v0.4.0 FEATURES.md §11 — head-local OAuth account pool assembly and control projection.
package splice.app.head

import splice.app.provider.Wired
import splice.control.HeadAccountAuthSource
import splice.control.HeadAccountPinSource
import splice.control.HeadAccountPoolSource
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountSwitchView
import splice.control.HeadAccountView
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountPoolView
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown

internal class HeadAccountPools {
    private val elapsedNow = ProcessElapsedNow()
    private val accountNow = WallClock(System::currentTimeMillis)

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

    // V4-132: a NAMED class, not the bare HeadAccountPoolSource { } lambda this returned before —
    // a fun-interface lambda cannot ALSO implement HeadAccountPinSource, and widening
    // HeadAccountPoolSource itself would break every test double that implements it by SAM
    // conversion. AuthRoutes discovers the pin capability with a checked cast
    // (`managed.accountPool as? HeadAccountPinSource`), the same idiom StatuslineRoute already
    // uses for HeadPerfSkipSource.
    fun source(pool: AccountPool?): HeadAccountPoolSource? = pool?.let(::PoolSource)

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
                authExcludedUntilEpochMillis = account.authExcludedUntilEpochMillis,
                authExclusionReason = account.authExclusionReason,
                fiveHourWindowSeconds = account.fiveHourWindowSeconds,
                sevenDayWindowSeconds = account.sevenDayWindowSeconds,
            )
        },
        lastSwitch = view.lastSwitch?.let { switch ->
            HeadAccountSwitchView(switch.from, switch.to, switch.reason, switch.atEpochMillis)
        },
    )

    /** An INNER class (not a top-level one — the wall bans those in main sources) so it can reach
     *  the outer [controlView] without widening it past `private`. */
    private inner class PoolSource(private val pool: AccountPool) : HeadAccountPoolSource, HeadAccountPinSource {
        override fun view(sessionId: String?): HeadAccountPoolView = controlView(pool.view(sessionId)).copy(
            pinnedLabel = pool.pinned(),
            nextTargetLabel = pool.nextTargetLabel(),
        )

        override fun pin(label: String): Boolean = pool.pin(label)
    }
}
