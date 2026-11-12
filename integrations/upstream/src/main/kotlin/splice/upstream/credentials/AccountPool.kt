// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.upstream.credentials

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import splice.core.auth.ClientAuthProvider
import splice.core.auth.SavedPins
import splice.core.failure.AccountBlock
import splice.core.util.LruSizing
import splice.core.util.WallClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val MAX_TRACKED_SESSIONS = 4_096

/** The answer [AccountPool.select] returns: an account was chosen for the turn, or every account is
 *  exhausted and the turn is refused before admission. The exhaustion is a VALUE on the return type,
 *  never a thrown exception (kt-no-exception-as-outcome): the compiler then checks the caller's
 *  `when`, so a new selection answer cannot slip past an unexhaustive caller the way a thrown
 *  refusal slipped past every `catch (e: AllAccountsExhausted)` in the tree.
 *
 *  IT LIVES HERE, with its producer, rather than in AccountSelection.kt where V4-99 first declared
 *  it. That file had reached 11 declared types and a concentration ratio of 3.11 — band HIGH, a god
 *  file by the campaign's own census — and the split the ratchet points at is by CONCEPT, not by
 *  line count. The answer to `select` belongs beside `select`; the file named for the account
 *  selection then keeps the selection ITSELF. Same package, so this is a relocation: no call site
 *  changes, no import changes, no behaviour changes. */
public sealed class Selection {
    /** An account was chosen; [account] is the immutable per-turn choice. */
    public class Chosen(public val account: AccountSelection) : Selection()

    /** No account could start the turn; [earliestResetEpochSeconds] is the soonest any account
     *  resets, null when no account reported a reset. The line a person reads is written from it, or from [blocked]:
     *  a login its provider refused until its owner acts, whose step that line names instead. */
    public class Exhausted(
        public val earliestResetEpochSeconds: Long?,
        public val blocked: AccountBlock? = null,
    ) : Selection()
}

/** A head-local OAuth account pool. Each selection owns one immutable login choice and its probe lease. */
public class AccountPool(
    accounts: List<PoolAccount>,
    private val now: WallClock,
    private val evidenceLoads: AccountEvidenceLoads? = null,
) {
    private class AccountMembership(accounts: List<PoolAccount>) {
        val accounts: List<PoolAccount> = accounts.toList().also {
            require(it.count(PoolAccount::primary) == 1) { "account pool must have exactly one primary" }
        }
        val byLabel: Map<String, PoolAccount> = this.accounts.associateBy(PoolAccount::label)
        val primary: PoolAccount = this.accounts.single(PoolAccount::primary)

        init {
            require(this.accounts.isNotEmpty()) { "account pool must not be empty" }
            require(byLabel.size == this.accounts.size) { "account labels must be unique" }
        }
    }

    private val membership = AtomicReference(AccountMembership(accounts))
    private val membershipMonitor = Any()

    /** Native login writes outside splice. Reconcile its generation before reading selector state. */
    @Volatile
    public var membershipRefresh: AccountMembershipRefresh? = null

    /** Every read of the pool starts here, so a pin whose login read a new limit lets go before anything reads the pin
     *  (Marcos, Nov 3, 2026: "make the pin let go at the first limit reading instead of pulling seats back"). */
    private val liveMembership: AccountMembership
        get() {
            membershipRefresh?.refresh()
            val current = membership.get()
            pins.letGo(current.byLabel, now(), onPinsLetGo)
            return current
        }

    /** Hears a login join the pool: the head's held turns may move to it (failure-handling slice 5). */
    @Volatile
    public var onJoined: Runnable? = null

    private val joined = AtomicLong(0L)

    /** How many times a login became one a waiting turn may take: a label the pool did not hold, a label that now
     *  sends another credential (Claude Code signed in to another account in the same place), or a login the person
     *  said to ask again. A turn that a login refused before the newest of these may be offered that login again:
     *  what was refused then may be another account now, and a login still spent is kept out by its own hold. */
    public val joins: Long get() = joined.get()

    /** Hears the pins that let go at a limit reading, so the saved pins let go too and a restart does not restore them. */
    @Volatile
    public var onPinsLetGo: PinsLetGo? = null

    /** Publishes membership only. Existing session choices, order, cooldown objects and leased turns survive. A label
     *  the pool did not hold before is a login that joined, and so is a label that now sends another credential: on
     *  Nov 12, 2026 Claude Code was signed in to an account with room while eleven turns waited on a spent model, the
     *  label stayed native:claude, and no waiting turn moved. [onJoined] hears both. */
    public var members: List<PoolAccount>
        get() = membership.get().accounts
        set(accounts) {
            val updated = AccountMembership(accounts)
            val before = synchronized(membershipMonitor) { membership.getAndSet(updated) }
            if (updated.accounts.any { before.byLabel[it.label]?.auth !== it.auth }) {
                joined.incrementAndGet()
                onJoined?.run()
            }
        }

    /** A forwarded caller alone is the legacy path, not a choice between stored logins. */
    public val active: Boolean
        get() = liveMembership.let { it.accounts.size > 1 || it.primary.auth !is ClientAuthProvider }

    // Access order keeps active sessions sticky without retaining every session the daemon ever saw.
    // Reads reorder the map too, so selection, views and reset share its monitor.
    private val sessions = LinkedHashMap<String, SessionAccount>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    )

    private val headLastSwitch = AtomicReference<AccountSwitch?>(null)

    // The login the head last served a new or moved session on: a change of it is the head's switch, once per change.
    private val headServing = AtomicReference<String?>(null)
    private val statelessLock = Any()
    private var statelessPrevious: SessionAccount? = null

    // V4-132: the REAL pin behind POST /api/auth/{head}/switch. Before this, `select` was
    // policy-only (FEATURES.md 4.5 "Manual switch"): even a session sitting on a deliberately
    // chosen backup fell back to primary on its very next turn, because [choose] always tried
    // primary first when the session's previous account was not already primary. A pin is tried
    // FIRST, ahead of primary — [candidates] below is the one order [choose] and [nextTargetLabel]
    // both walk, so a pin changes both the same way. An unavailable pin falls through to the same
    // policy as before (NEVER-BELOW-STATUS-QUO): pinning never wedges a head that would otherwise
    // still be serving turns on its own.
    private val pins = PinBook()
    private val orderedLabels = AtomicReference<List<String>>(emptyList())

    /** Operator priority, retained across runtime resets. Empty selects sticky, soonest-reset mode. */
    public var order: List<String>
        get() = orderedLabels.get().filter(membership.get().byLabel::containsKey)
        set(labels) {
            require(labels.distinct().size == labels.size) { "account order contains duplicate labels" }
            require(labels.all(membership.get().byLabel::containsKey)) { "account order names an unknown account" }
            orderedLabels.set(java.util.List.copyOf(labels))
        }

    /** The exact candidate policy, including an explicit runtime pin and fallback accounts. */
    public fun effectiveOrder(): List<String> {
        val current = liveMembership
        current.accounts.forEach { it.refreshCredentialEvidence() }
        return candidates(null, current, pins.forSession(null)).map(PoolAccount::label)
    }

    /** Chooses before acceptance. Null sessions re-evaluate policy without becoming sticky.
     *  Nonempty [excluded] skips refused logins and permits only a free login, never a held fallback.
     *  Returns [Selection.Chosen] with an immutable choice, or [Selection.Exhausted] as a refusal value. */
    public fun select(sessionId: String?, excluded: Set<String> = emptySet()): Selection =
        select(sessionId, excluded, null)

    /** A changed caller login takes precedence over automatic stickiness, but never an operator pin. [model] is the
     *  model the turn asks for: a login whose provider refused another model for the week serves it (Nov 3, 2026). */
    public fun select(
        sessionId: String?,
        excluded: Set<String>,
        callerCredentialKey: String?,
        model: String? = null,
    ): Selection {
        require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
        asked.asked(model)
        while (true) {
            val current = liveMembership
            current.accounts.forEach { it.refreshCredentialEvidence() }
            val caller = CallerLogin(callerCredentialKey, sessionId, current.accounts, model)
            val selected = selectionCommit.select(sessionId, excluded, current, caller)
            if (selected != null) return selected
        }
    }

    private val selectionCommit = SelectionCommit()

    /** The model this command's turns ask for, for a preview that has no turn of its own ([nextTargetLabel]). */
    public val asked: AskedModels = AskedModels()

    /** Suspending turn admission shares raw reads; synchronous control callers retain [select]. */
    public val awaitingEvidence: AwaitingEvidence = AwaitingEvidence()

    /** No suspension follows lease acquisition, so prompt cancellation cannot strand a probe lease. */
    public inner class AwaitingEvidence internal constructor() {
        public suspend fun select(
            sessionId: String?,
            excluded: Set<String> = emptySet(),
            callerCredentialKey: String? = null,
            model: String? = null,
        ): Selection {
            require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
            currentCoroutineContext().ensureActive()
            val loads = evidenceLoads ?: return this@AccountPool.select(sessionId, excluded, callerCredentialKey, model)
            asked.asked(model)
            while (true) {
                loads.ensureActive()
                val current = runInterruptible(loads.ioDispatcher) { liveMembership }
                current.accounts.forEach { it.refreshCredentialEvidence(loads) }
                val caller = runInterruptible(loads.ioDispatcher) {
                    CallerLogin(callerCredentialKey, sessionId, current.accounts, model)
                }
                currentCoroutineContext().ensureActive()
                loads.ensureActive()
                val selected = selectionCommit.select(sessionId, excluded, current, caller)
                if (selected != null) return selected
            }
        }
    }

    private inner class SelectionCommit {
        /** Membership publication cannot race lease acquisition or the session-state commit. */
        fun select(
            sessionId: String?,
            excluded: Set<String>,
            current: AccountMembership,
            caller: CallerLogin,
        ): Selection? = synchronized(membershipMonitor) {
            if (membership.get() === current) commit(sessionId, excluded, current, caller) else null
        }

        private fun commit(
            sessionId: String?,
            excluded: Set<String>,
            current: AccountMembership,
            caller: CallerLogin,
        ): Selection {
            val at = now()
            if (sessionId == null) {
                return synchronized(statelessLock) {
                    val chosen = selected(statelessPrevious, at, excluded, current, caller)
                    chosen.second?.let { statelessPrevious = it }
                    chosen.first
                }
            }
            return synchronized(sessions) {
                val chosen = selected(sessions[sessionId], at, excluded, current, caller)
                chosen.second?.let { session ->
                    sessions[sessionId] = session
                    if (sessions.size > MAX_TRACKED_SESSIONS) sessions.remove(sessions.keys.first())
                }
                chosen.first
            }
        }
    }

    private fun selected(
        previous: SessionAccount?,
        at: Long,
        excluded: Set<String>,
        current: AccountMembership,
        caller: CallerLogin,
    ): Pair<Selection, SessionAccount?> {
        val chosen = choose(previous, at, excluded, current, caller) ?: return Selection.Exhausted(
            AccountAvailability.earliestReset(current.accounts, at, caller.model),
            OwnerBlocks.named(current.accounts, at),
        ) to null
        // A new session starts relative to primary even when its credential is missing: choosing
        // a backup is cache-cold on that first turn, and the session's own last switch says so.
        val prior = previous ?: SessionAccount(current.primary.label, null)
        val pinLabel = pins.forSession(caller.session)
        val policy = AccountSwitchReason.Policy(pinLabel, orderedLabels.get(), caller.model)
        val moved = prior.takeIf { it.label != chosen.account.label }?.let {
            val reason = caller.movedBy(previous, chosen.account, pinLabel)
                ?: AccountSwitchReason.of(current.byLabel[it.label], chosen.account, at, policy)
            AccountSwitch(it.label, chosen.account.label, reason, at)
        }
        // The head switched only when the login it serves new or moved sessions on changed: once per change, never
        // once per session that starts on a backup while the primary is held (about 10,000 a day on claude-splice,
        // Oct 12). A session staying on its login, and the first login served after a start, are no switch of the head.
        val headFrom = if (previous == null || moved != null) headServing.getAndSet(chosen.account.label) else null
        val headSwitch = headFrom?.takeIf { it != chosen.account.label }?.let {
            val reason = AccountSwitchReason.of(current.byLabel[it], chosen.account, at, policy)
            AccountSwitch(it, chosen.account.label, reason, at)
        }
        headSwitch?.let(headLastSwitch::set)
        val warm = caller.keepsCache(previous, moved, at)
        // The clock follows the login: a session that stays keeps its stamp, one that moves starts with none, and a
        // turn stamps it only by ending cleanly (AccountSelection.markTurnSucceeded), never by being selected.
        val served = caller.stampFor(previous, moved, now)
        val selection = AccountSelection(chosen.account, moved, chosen.lease, headSwitch, warm, served)
        val session = SessionAccount(
            chosen.account.label,
            moved ?: previous?.lastSwitch,
            caller.remembered(previous),
            served,
        )
        return Selection.Chosen(selection) to session
    }

    /** Safe state for operator surfaces; null names head-wide state, never another session's choice. A [model] reads
     *  each login as a turn of that model meets it (a turn moving on), null as the console shows the login. */
    public fun view(sessionId: String?, model: String? = null): AccountPoolView {
        val at = now()
        val session = synchronized(sessions) { sessionId?.let(sessions::get) }
        val current = liveMembership
        val accounts = current.accounts
        accounts.forEach { it.refreshCredentialEvidence() }
        val pinLabel = pins.forSession(sessionId)
        val stay = CallerLogin(null, sessionId, accounts, model).previewStay(session, current.byLabel, pinLabel, at)
        return AccountPoolView(
            selectedLabel = session?.label?.takeIf(current.byLabel::containsKey),
            accounts = accounts.map { account -> AccountViewing(at, model)(account, session?.label) },
            lastSwitch = if (sessionId == null) headLastSwitch.get() else session?.lastSwitch,
            blockedUntilEpochSecondsByLabel = accounts.mapNotNull { account ->
                AccountAvailability.blockedUntil(account, at, model)?.let { account.label to it }
            }.toMap(),
            // Where the command moves when the account it would use next runs out: the same pick over the order
            // [nextTargetLabel] walks, with that account left out. Here, not a function: the class is at the ceiling.
            followingLabel = AccountAvailability.upNext(
                candidates(session?.label, current, pinLabel, model),
                at,
                stay,
                model,
            ).getOrNull(1)?.label,
        )
    }

    /** The next reset when a provider quota hold blocks all credential-selectable accounts.
     *  Authentication-only holds have no provider reset. Missing credentials and auth exclusions
     *  cannot make the pool appear free. Reuse selection's
     *  reset calculation, rather than re-deriving a second account horizon. A property,
     *  not a function: the class sits at detekt's 15-function ceiling (see [AccountAvailability]). */
    public val providerResetForMs: Long
        get() {
            val accounts = liveMembership.accounts
            if (accounts.none { it.cooldown.providerUnavailableForMs() > 0L }) return 0L
            accounts.forEach { it.refreshCredentialEvidence() }
            val at = now()
            val free = accounts.any {
                it.credentialStatus(at).selectable && !it.quotaHeld && it.cooldown.providerUnavailableForMs() <= 0L
            }
            if (free) return 0L
            val reset = AccountAvailability.earliestReset(accounts, at) ?: return 0L
            return (TimeUnit.SECONDS.toMillis(reset) - at).coerceAtLeast(0L)
        }

    /** Independent product sends observe the captured login's actual cooldown, never a sibling's. */
    public val responseCooldowns: Map<String, splice.upstream.retry.RateLimitCooldown>
        get() = liveMembership.byLabel.mapValues { it.value.cooldown }

    /** Clears only runtime stickiness/cooldowns; persisted quota and credential files stay untouched. The operator's
     *  policy stays too: the saved order, and the pins, which the daemon saves and puts back when it builds the head.
     *  Every head start runs this reset (HeadLifecycle), so a pin it cleared died at the restart that was to keep it
     *  (Nov 3, 2026: the install of #461 dropped Marcos's pin on account-3 that way). */
    public fun reset() {
        synchronized(sessions) { sessions.clear() }
        synchronized(statelessLock) { statelessPrevious = null }
        headLastSwitch.set(null)
        headServing.set(null)
        membership.get().accounts.forEach {
            it.cooldown.clear()
            it.cooldown.clearUnavailable()
            it.resetCredentialAvailability()
        }
    }

    /** The person says [label] can be asked again: they signed it in again, or pressed Try again on its card once
     *  what its provider's block names was done. The hold a refusal put on its credential ends now, so the next turn
     *  that reaches it asks the provider and the provider's answer decides. A block its owner has to lift stays named
     *  until that answer serves a turn ([AccountCredentialEligibility.askAgain]). A limit it read stays: that is the
     *  account's, not the credential's. A credential file the client rewrites by itself does none of this; only the
     *  person's own act does. Until Nov 11, 2026 a login whose organization's payment was fixed was not asked again,
     *  signed in again or not, until its hold ran out. The turns waiting on other logins hear it as a login that
     *  joined ([onJoined]). False when [label] names no account here. */
    public fun askAgain(label: String): Boolean {
        val account = liveMembership.byLabel[label] ?: return false
        account.askCredentialAgain(now())
        joined.incrementAndGet()
        onJoined?.run()
        return true
    }

    /** Pins [label] as the account [select] tries FIRST, ahead of the primary preference, until [unpin] or until the
     *  login reads a limit it did not read now; a [reset] keeps it. False (nothing pinned) when [label] names no account
     *  here. */
    public fun pin(label: String, sessionId: String? = null): Boolean {
        // The login's own reading first, so a limit it read since the last read lets go of the pins already on it before
        // this press sets what the login is pinned over: a session's press must not carry the head's pin over a limit.
        liveMembership.byLabel[label]?.refreshCredentialEvidence()
        val account = liveMembership.byLabel[label] ?: return false
        pins.pin(account, sessionId, now())
        return true
    }

    public fun unpin(sessionId: String? = null) {
        pins.unpin(sessionId)
    }

    /** Every pin as saved state: read it to keep the pins, set it to put them back after a daemon restart (Nov 3, 2026:
     *  a Use now pin lived here alone, and every restart dropped it). A pin whose login the pool does not hold now is
     *  kept, as one whose login leaves later is: [pinned] and [select] pass over it until that login is back. A property,
     *  not functions: the class sits at detekt's 15-function ceiling. */
    public var savedPins: SavedPins
        get() = pins.saved()
        set(saved) = pins.restore(saved)

    /** The currently pinned label, or null when nothing is pinned. Safe for an operator surface —
     *  no credential material, just the label [select] already exposes elsewhere. */
    public fun pinned(sessionId: String? = null): String? =
        liveMembership.byLabel.let { known -> pins.forSession(sessionId)?.takeIf(known::containsKey) }

    /** The label [select] would choose next for [sessionId] (null = head-wide), without acquiring
     *  a credential lease — a read-only probe for an operator surface (GET /api/accounts "the next
     *  target by the real selector order"). Walks the exact same [candidates] order [choose] does,
     *  testing only [available]: [acquireIfAvailable] takes a probe lease, which this must not. */
    public fun nextTargetLabel(sessionId: String? = null, model: String? = null): String? {
        val previous = synchronized(sessions) { sessionId?.let { sessions[it] } }
        val current = liveMembership
        current.accounts.forEach { it.refreshCredentialEvidence() }
        val pinLabel = pins.forSession(sessionId)
        val at = now()
        // A warm session keeps its login through a short hold, so the preview names it first, as [choose] does.
        val stay = CallerLogin(null, sessionId, current.accounts, model)
            .previewStay(previous, current.byLabel, pinLabel, at)
        val order = candidates(previous?.label, current, pinLabel, model)
        return AccountAvailability.upNext(order, at, stay, model).firstOrNull()?.label
    }

    /** Session listings read every listed session through ONE membership read. A property, not a function: the class
     *  is at detekt's 15-function ceiling. */
    public val listed: SessionListing = SessionListing()

    /** [view] reconciles membership and re-reads every login's credentials on each call, so a listing that asked it per
     *  row read the logins hundreds of times. [choices] reconciles once and reads only the per-session state. */
    public inner class SessionListing internal constructor() {
        public fun choices(sessionIds: Collection<String>): Map<String, AccountSessionChoice> {
            val known = liveMembership.byLabel
            val held = synchronized(sessions) { sessionIds.associateWith(sessions::get) }
            return held.mapValues { (id, session) ->
                AccountSessionChoice(
                    selectedLabel = session?.label?.takeIf(known::containsKey),
                    pinnedLabel = pins.forSession(id)?.takeIf(known::containsKey),
                    lastSwitch = session?.lastSwitch,
                )
            }
        }
    }

    /** One order for selection and its preview. A session stays on its free login in either mode, until a limit
     *  (Marcos, Oct 3: "a session stays put until a limit"); where it moves next is the quota that resets soonest, or
     *  the person's saved order, which also places a session that has no login yet. */
    private fun candidates(
        previousLabel: String?,
        current: AccountMembership,
        pinLabel: String?,
        model: String? = null,
    ): List<PoolAccount> {
        val byLabel = current.byLabel
        val primary = current.primary
        val pin = pinLabel?.let(byLabel::get)
        val previous = previousLabel?.let(byLabel::get)
        val byReset = current.accounts.sortedWith(AccountAvailability.resetOrder(now(), model))
        val ordered = orderedLabels.get().mapNotNull(byLabel::get)
        val policy = if (ordered.isEmpty()) {
            listOfNotNull(previous) + byReset
        } else {
            listOfNotNull(previous) + ordered + listOfNotNull(primary) + byReset
        }
        return (listOfNotNull(pin) + policy).distinctBy { it.label }
    }

    /** The first free login in [candidates] order; when none is free, the credential-selectable login whose hold
     *  resets first. Admission does not invent a quota refusal: the selected login's own answer holds the turn.
     *  An in-turn handoff [excluded] takes only a free login, so it cannot bounce among exhausted logins. */
    private fun choose(
        previous: SessionAccount?,
        at: Long,
        excluded: Set<String>,
        current: AccountMembership,
        caller: CallerLogin,
    ): ChosenAccount? {
        val pinLabel = pins.forSession(caller.session)
        val preference = listOfNotNull(
            pinLabel?.let(current.byLabel::get),
            caller.preferred(previous)?.let(current.byLabel::get),
        )
        val order = (preference + candidates(caller.continuesFrom(previous), current, pinLabel, caller.model))
            .distinctBy(PoolAccount::label)
            .filter { it.label !in excluded }
        // A warm session keeps its login through a short rate limit, which admission waits out: a move rebuilds its
        // whole cache on the next login (Marcos, Oct 19). A pin or a changed caller login elsewhere still wins.
        val stay = caller.stays(previous, current.byLabel, preference, at)?.takeIf { it.label !in excluded }
        val free = AccountAvailability.preferredFree(order, at, caller.model)
        val held = AccountAvailability.nearestHeld(order, at, caller.model)
        val eligible = listOfNotNull(stay.takeIf { free.isNotEmpty() }) + when {
            excluded.isNotEmpty() -> free
            else -> free.ifEmpty { held }
        }
        // A login its provider blocked until its owner acts is asked last, and only by a turn that no other login took
        // from: a turn that moved off a login asks only a free one.
        val lastResort = OwnerBlocks.lastResort(order, at).takeIf { excluded.isEmpty() }.orEmpty()
        return (eligible + lastResort).distinctBy(PoolAccount::label).firstNotNullOfOrNull { account ->
            account.acquireCredential(at, now)?.let { lease -> ChosenAccount(account, lease) }
        }
    }

    private data class ChosenAccount(
        val account: PoolAccount,
        val lease: AccountCredentialEligibility.Lease,
    )
}
