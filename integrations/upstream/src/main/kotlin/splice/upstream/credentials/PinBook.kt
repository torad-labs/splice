// NEW: v0.4.0 spec section 11 ("Per-session and per-head pinning select the intended account under concurrent
// sessions"). The pool's manual pins, held apart from AccountPool so that class stays under detekt's function
// ceiling. A session pin outranks the head pin for THAT session only; a pin for one session never reaches another.
//
// Nov 3, 2026: a pin lets go at its login's first limit reading (Marcos, through Simon: "make the pin let go at the
// first limit reading instead of pulling seats back"). That evening Anthropic refused Fable on the pinned account-3 for
// the week; the pin stayed, account-3 still served Opus, and a short limit elsewhere sent a seat back to it for six
// minutes until a usage reading showed its week full.
package splice.upstream.credentials

import splice.core.auth.SavedPins

/** Session pins are bounded like the pool's own sticky sessions: the oldest goes first, so a client minting a new
 *  session id per run cannot grow this without limit. */
private const val MAX_SESSION_PINS = 512

/** The limits a pin lets go on, as [PinBook] names them: the provider naming the whole login's plan window spent,
 *  the provider holding the account, and a usage reading at 100%. One model's week is named by its model. */
private const val WHOLE_LOGIN_SPENT = "whole login spent"
private const val PROVIDER_HELD = "provider held"
private const val READS_FULL = "reads full"

internal class PinBook {
    private var head: String? = null
    private val sessions = LinkedHashMap<String, String>()

    /** The limits each pinned login read when Use now was pressed and still reads, saved with the pins: a pin lets go at
     *  the first limit its login reads beyond them. */
    private val pinnedOver = HashMap<String, Set<String>>()

    /** Pins [account] for [sessionId], or for the whole head when null. A limit it already reads keeps the pin: Use
     *  now on a login refused Fable alone still serves Opus (Nov 3, 2026). */
    @Synchronized
    fun pin(account: PoolAccount, sessionId: String?, at: Long) {
        val label = account.label
        account.refreshCredentialEvidence()
        pinnedOver[label] = limits(account, at)
        if (sessionId == null) {
            head = label
            return
        }
        sessions.remove(sessionId)
        sessions[sessionId] = label
        if (sessions.size > MAX_SESSION_PINS) sessions.remove(sessions.keys.first())
    }

    /** Drops one session's pin, or the head-wide pin when [sessionId] is null. */
    @Synchronized
    fun unpin(sessionId: String?) {
        if (sessionId == null) head = null else sessions.remove(sessionId)
    }

    /** What [sessionId] is pinned to: its own pin, else the head's. A null session sees the head's only. */
    @Synchronized
    fun forSession(sessionId: String?): String? = sessionId?.let(sessions::get) ?: head

    /** The pin this session set for itself, ignoring the head's. */
    @Synchronized
    fun ownedBy(sessionId: String?): String? = sessionId?.let(sessions::get)

    /** Every pin, as it is saved between daemon runs, with what each pinned login is pinned over. */
    @Synchronized
    fun saved(): SavedPins = SavedPins(head, LinkedHashMap(sessions), pinnedOver.filterKeys(pinnedLabels()::contains))

    /** Replaces every pin with [pins], as a daemon run saved them; the newest sessions are kept within the bound. Each
     *  pin is weighed against what it was saved over, so a limit its login read after that, before this run or before
     *  a refused write put these back, lets it go at the first read. A login saved with nothing for it (a file from
     *  before [SavedPins.over]) is weighed against that first read. */
    @Synchronized
    fun restore(pins: SavedPins) {
        head = pins.head
        sessions.clear()
        pins.sessions.entries.toList().takeLast(MAX_SESSION_PINS).forEach { (session, label) ->
            sessions[session] = label
        }
        pinnedOver.clear()
        pinnedOver.putAll(pins.over.filterKeys(pinnedLabels()::contains))
    }

    /** Lets go of every pin whose login reads a limit it was not pinned over, and tells [changed] whenever a pin let go
     *  or what one is pinned over shrank, inside this lock, so two changes are saved in the order they happened. A
     *  limit that clears leaves what the pin is pinned over, so its return is a new reading. A login the pool does not
     *  hold now keeps its pin until it is back. It reads each login's last observation, not a new one: this runs on
     *  every read of the pool, and a turn's choice and the console's view observe every login after it, so a usage
     *  reading lets its pin go at the read after the one that observed it, while that turn already passes over a login
     *  that reads full. */
    @Synchronized
    fun letGo(accounts: Map<String, PoolAccount>, at: Long, changed: PinsLetGo?) {
        val pinned = pinnedLabels()
        pinnedOver.keys.retainAll(pinned)
        val before = HashMap(pinnedOver)
        val gone = pinned.filterTo(mutableSetOf()) { label ->
            val account = accounts[label] ?: return@filterTo false
            val reads = limits(account, at)
            val over = pinnedOver[label] ?: reads
            pinnedOver[label] = over intersect reads
            (reads - over).isNotEmpty()
        }
        if (head in gone) head = null
        sessions.values.removeAll(gone)
        pinnedOver.keys.removeAll(gone)
        if (gone.isNotEmpty() || pinnedOver != before) changed?.letGo(gone, HashMap(pinnedOver))
    }

    private fun pinnedLabels(): Set<String> = sessions.values.toSet() + listOfNotNull(head)

    /** The limits [account] reads at [at] that a pin lets go on. A short rate limit is none: a turn waits it out on the
     *  same login. */
    private fun limits(account: PoolAccount, at: Long): Set<String> = buildSet {
        account.cooldown.planHold.live()?.let { add(WHOLE_LOGIN_SPENT) }
        account.cooldown.planHold.models().forEach { add("week of ${it.model}") }
        if (account.quotaHeld) add(PROVIDER_HELD)
        if (AccountAvailability.fullReading(account, at)) add(READS_FULL)
    }
}
