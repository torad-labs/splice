// NEW: server mode (docs/specs/server-mode.md, F3 and decision 6). What one person may hold of the shared box: so many
// turns in flight at once, and so many started in any minute. A turn past either cap is refused at once, never queued,
// with how long to wait and a sentence naming the cap, and it counts toward neither. Everyone else is served: each
// person is counted alone. A slot is given back when its turn ends or its client leaves, whichever is first.
package splice.server

import splice.core.topology.ServerLimits
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One admitted turn's place among its person's turns in flight, given back once however often [release] is called. */
internal class TurnSlot(private val use: PersonUse) {
    private val released = AtomicBoolean()

    fun release() {
        if (released.compareAndSet(false, true)) use.ended()
    }
}

/** What asking to start a turn came to. */
internal sealed class LimitVerdict {
    class Admitted(val slot: TurnSlot) : LimitVerdict()

    /** Refused: [why] names the cap, and the person may try again in [retryAfterSeconds]. */
    data class Refused(val why: String, val retryAfterSeconds: Long) : LimitVerdict()
}

/** One person's turns: how many are in flight, and when each of the last minute's began. */
internal class PersonUse {
    private var inflight = 0
    private val starts = MinuteStarts()

    /** Starts a turn at [now] under [caps], or says how the caps refuse it. */
    @Synchronized
    fun begin(person: String, caps: ServerLimits, now: Long): LimitVerdict {
        val inflightCap = caps.inflightPerPerson
        val rpmCap = caps.rpmPerPerson
        if (inflightCap > 0 && inflight >= inflightCap) {
            val why = "$person already has $inflight turns in flight, the most one person may hold here " +
                "(inflight_per_person = $inflightCap); the next can start when one of them ends"
            return LimitVerdict.Refused(why, INFLIGHT_RETRY_SECONDS)
        }
        // The whole seconds until the oldest of the minute's turns leaves it: a retry then is served.
        val wait = starts.waitFor(rpmCap, now)
        if (wait != null) {
            val why = "$person started $rpmCap turns in the last minute, the most one person may start here " +
                "(rpm_per_person = $rpmCap); the next can start in $wait s"
            return LimitVerdict.Refused(why, wait)
        }
        inflight++
        starts.add(now)
        return LimitVerdict.Admitted(TurnSlot(this))
    }

    @Synchronized
    fun ended() {
        inflight--
    }

    /** How many of this person's turns are in flight now. */
    @Synchronized
    fun held(): Int = inflight
}

internal class PersonLimits(
    /** `[server.limits]`: what holds each person the operator set no cap of their own for. */
    private val server: ServerLimits,
    private val people: ServerPeople,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val uses = ConcurrentHashMap<String, PersonUse>()

    /** Starts a turn for [person], or refuses it. An admitted turn's slot must be released when the turn ends. */
    fun begin(person: String): LimitVerdict =
        uses.computeIfAbsent(person) { PersonUse() }.begin(person, capsOf(person), clock.millis())

    /** How many of [person]'s turns are in flight now (F6). */
    fun inflight(person: String): Int = uses[person]?.held() ?: 0

    /** The most turns [person] may hold in flight at once; null when nothing caps them (F6). */
    fun inflightCap(person: String): Int? = capsOf(person).inflightPerPerson.takeIf { it > 0 }

    /** [person]'s caps: their own where the operator set one, else the server's. */
    private fun capsOf(person: String): ServerLimits {
        val own = people.of(person)
        return ServerLimits(own.inflight ?: server.inflightPerPerson, own.rpm ?: server.rpmPerPerson)
    }
}

// why: nothing says when a turn in flight will end, a short answer in a second or a long one in minutes. One second
// is the shortest wait Retry-After can name, and Claude Code backs off further on its own while the refusals repeat.
private const val INFLIGHT_RETRY_SECONDS = 1L
