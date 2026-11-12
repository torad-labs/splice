// NEW: v0.4.0 FEATURES.md §11 — head-local OAuth account pool assembly and control projection.
package splice.app.head

import splice.accounts.order.AccountOrderStore
import splice.accounts.order.AccountPinStore
import splice.accounts.order.HeadAccountOrderSource
import splice.accounts.pool.HeadAccountAskSource
import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountCredential
import splice.accounts.pool.HeadAccountHolds
import splice.accounts.pool.HeadAccountPinSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountQuota
import splice.accounts.pool.HeadAccountSessionChoice
import splice.accounts.pool.HeadAccountSessionSource
import splice.accounts.pool.HeadAccountSessions
import splice.accounts.pool.HeadAccountSwitchView
import splice.accounts.pool.HeadAccountView
import splice.accounts.pool.HeadAccountWindow
import splice.app.provider.Wired
import splice.app.provider.WiredAccount
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.REFUSAL_FIELD
import splice.core.auth.SavedPins
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.head.usage.TrackedAccountQuota
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountEvidenceLoads
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountPoolView
import splice.upstream.credentials.PinsLetGo
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import java.io.IOException

internal class HeadAccountPools(private val evidenceLoads: EvidenceLoadFactory? = null) {
    fun interface EvidenceLoadFactory {
        operator fun invoke(): AccountEvidenceLoads
    }

    private val elapsedNow = ProcessElapsedNow()
    private val accountNow = WallClock(System::currentTimeMillis)

    /** A pool is a CHOICE. Discovery always yields the primary, so a head holding one account keeps
     *  the pre-0.4.0 path end to end (no per-turn selection, no account segment on the statusline,
     *  no account_pool on /api/auth) and renders byte-identical to a head that never had a pool. */
    fun build(
        wired: Wired,
        trackers: Map<String, QuotaTracker>,
        holds: Map<String, ProviderHoldStore> = emptyMap(),
        primaryQuota: QuotaTracker? = null,
    ): AccountPool? {
        if (!pooled(wired)) {
            if (wired.auth !is ClientAuthProvider || primaryQuota == null) return null
            return AccountPool(
                listOf(
                    PoolAccount(
                        label = splice.app.auth.claude.OWN_SIGN_IN_LABEL,
                        primary = true,
                        auth = wired.auth,
                        quota = TrackedAccountQuota(primaryQuota),
                        cooldown = RateLimitCooldown(elapsedNow),
                    ),
                ),
                accountNow,
                evidenceLoads = evidenceLoads?.invoke(),
            )
        }
        val accounts = wired.accounts.map { account ->
            val tracker = trackers.getValue(account.label)
            PoolAccount(
                label = account.label,
                primary = account.primary,
                auth = account.auth,
                quota = TrackedAccountQuota(tracker, account.quota.read),
                cooldown = RateLimitCooldown(elapsedNow, store = holds[account.label]),
                credentialPresent = account.credential.present,
                extraHeaders = account.extraHeaders,
            )
        }
        return AccountPool(accounts, accountNow, evidenceLoads = evidenceLoads?.invoke())
    }

    // V4-132: a NAMED class, not the bare HeadAccountPoolSource { } lambda this returned before —
    // a fun-interface lambda cannot ALSO implement HeadAccountPinSource, and widening
    // HeadAccountPoolSource itself would break every test double that implements it by SAM
    // conversion. SwitchRoute discovers the pin capability with a checked cast
    // (`head.pool as? HeadAccountPinSource`), the same idiom StatuslineRoute already
    // uses for HeadPerfSkipSource.
    fun source(pool: AccountPool?): HeadAccountPoolSource? = pool?.let { PoolSource(it) }

    /** The head's account source with its saved policy: the operator's order, and, given [pins], the pins the last
     *  daemon run kept, put back before the first turn and saved again at every pin and unpin (Nov 3, 2026). */
    fun source(
        pool: AccountPool?,
        head: String,
        orders: AccountOrderStore,
        pins: AccountPinStore? = null,
    ): HeadAccountPoolSource? = pool?.let {
        val labels = it.effectiveOrder().toSet()
        it.order = orders.order(head).filter(labels::contains)
        if (pins != null) {
            it.savedPins = pins.pins(head)
            // A pin lets go at its login's first limit reading, and the saved one with it, so no restart restores it.
            it.onPinsLetGo = PinsLetGo { gone, over -> pins.release(head, gone, over) }
        }
        PoolSource(it, head, orders, pins)
    }

    fun authSource(wired: Wired): HeadAccountAuthSource? =
        if (pooled(wired) || wired.auth is ClientAuthProvider) {
            HeadAccountAuthSource {
                wired.liveAccounts.associate { account -> account.label to described(account) }
            }
        } else {
            null
        }

    /** A refused credential (V4-410) says why in its own description, beside `auth_path`, so /api/accounts and
     *  /api/auth show the sentence. Its reader already points at a name splice never creates, so this opens nothing. */
    private suspend fun described(account: WiredAccount): AuthDescription {
        val description = account.auth.describe()
        return account.credential.refusal
            ?.let { description.copy(fields = description.fields + (REFUSAL_FIELD to it)) }
            ?: description
    }

    private fun pooled(wired: Wired): Boolean = wired.accounts.size > 1 || wired.accounts.any { it.nativePlace != null }

    private fun controlView(view: AccountPoolView): HeadAccountPoolView = HeadAccountPoolView(
        selectedLabel = view.selectedLabel,
        blockedUntilEpochSecondsByLabel = view.blockedUntilEpochSecondsByLabel,
        accounts = view.accounts.map { account ->
            HeadAccountView(
                label = account.label,
                primary = account.primary,
                selected = account.selected,
                available = account.available,
                plan = account.plan,
                quota = HeadAccountQuota(
                    fiveHour = HeadAccountWindow(
                        usedPercent = account.quota.fiveHour.usedPercent,
                        resetEpochSeconds = account.quota.fiveHour.resetEpochSeconds,
                        windowSeconds = account.quota.fiveHour.windowSeconds,
                    ),
                    sevenDay = HeadAccountWindow(
                        usedPercent = account.quota.sevenDay.usedPercent,
                        resetEpochSeconds = account.quota.sevenDay.resetEpochSeconds,
                        windowSeconds = account.quota.sevenDay.windowSeconds,
                    ),
                    observedAtEpochSeconds = account.quota.observedAtEpochSeconds,
                    sevenDayModels = account.quota.sevenDayModels,
                    noUsageAtEpochSeconds = account.quota.noUsageAtEpochSeconds,
                    holds = HeadAccountHolds(account.quota.holds.wholeLogin, account.quota.holds.models),
                    readsFull = account.quota.readsFull,
                ),
                credential = HeadAccountCredential(
                    present = account.credential.present,
                    excludedUntilEpochMillis = account.credential.excludedUntilEpochMillis,
                    exclusionReason = account.credential.exclusionReason,
                    ownerBlock = account.credential.block,
                    renewable = account.credential.renewable,
                ),
            )
        },
        lastSwitch = view.lastSwitch?.let { switch ->
            HeadAccountSwitchView(switch.from, switch.to, switch.reason, switch.atEpochMillis)
        },
    )

    /** An INNER class (not a top-level one — the wall bans those in main sources) so it can reach
     *  the outer [controlView] without widening it past `private`. */
    private inner class PoolSource(
        private val pool: AccountPool,
        private val head: String? = null,
        private val orders: AccountOrderStore? = null,
        private val pins: AccountPinStore? = null,
    ) : HeadAccountPoolSource,
        HeadAccountPinSource,
        HeadAccountAskSource,
        HeadAccountOrderSource,
        HeadAccountSessionSource {
        override val active: Boolean get() = pool.active

        override fun askAgain(label: String): Boolean = pool.askAgain(label)

        override fun sessions(sessionIds: Collection<String>): HeadAccountSessions = HeadAccountSessions(
            headPinnedLabel = pool.pinned(null),
            bySession = pool.listed.choices(sessionIds).mapValues { (_, choice) ->
                HeadAccountSessionChoice(
                    choice.selectedLabel,
                    choice.pinnedLabel,
                    choice.lastSwitch?.let { HeadAccountSwitchView(it.from, it.to, it.reason, it.atEpochMillis) },
                )
            },
        )

        override fun view(sessionId: String?): HeadAccountPoolView = controlView(pool.view(sessionId)).copy(
            pinnedLabel = pool.pinned(sessionId),
            // The session's own next login: a session pinned before its first turn shows that pin, not the head's pick.
            // For the model the command's turns ask for, as the order route answers: asked with none, `splice status`
            // read "on" a login whose week had room and that had used up the model every session ran (Nov 12, 2026).
            nextTargetLabel = pool.nextTargetLabel(sessionId, pool.asked.usual()),
        )

        // Synchronized so two presses save in the order they pinned: each save writes the pins as they stand.
        @Synchronized
        override fun pin(label: String, sessionId: String?): Boolean {
            val before = pool.savedPins
            return pool.pin(label, sessionId).also { pinned -> if (pinned) savePinsOr(before) }
        }

        @Synchronized
        override fun unpin(sessionId: String?) {
            val before = pool.savedPins
            pool.unpin(sessionId)
            savePinsOr(before)
        }

        /** Saves the pins as they stand, or, when the disk refuses, puts [before] back and rethrows: a press the next
         *  restart would undo is answered as failed, never shown as taken. */
        private fun savePinsOr(before: SavedPins) {
            if (head == null) return
            try {
                pins?.set(head, pool.savedPins)
            } catch (e: IOException) {
                pool.savedPins = before
                throw e
            }
        }

        override fun order(): List<String> = pool.order

        override fun effectiveOrder(): List<String> = pool.effectiveOrder()

        // For the model the command's turns ask for: a login whose week for that model is used up is not where the
        // next turn goes, whatever room its own week has (Nov 12, 2026).
        override fun nextTarget(): String? = pool.nextTargetLabel(model = pool.asked.usual())

        override fun followingTarget(): String? = pool.view(null, pool.asked.usual()).followingLabel

        @Synchronized
        override fun setOrder(labels: List<String>): Boolean {
            val known = pool.effectiveOrder().toSet()
            if (labels.distinct().size != labels.size || labels.any { it !in known }) return false
            if (head != null) orders?.set(head, labels)
            pool.order = labels
            return true
        }
    }
}
