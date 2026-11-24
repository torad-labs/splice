// NEW: server mode (docs/specs/server-mode.md, F3, F12 and decision 6). What one person may hold of the shared box: so
// many turns in flight at once, and so many started in any minute. A turn past either cap is refused at once, never
// queued, with how long to wait and a sentence naming the cap, and it counts toward neither. Everyone else is served:
// each person is counted alone. A slot is given back when its turn ends or its client leaves, whichever is first. The
// tokens a person may use in a minute are capped from the ledger's rows (MinuteTokens); the cap that holds them, their
// own or the server's, is read here with the others, and so are the commands they may use (CommandAccess).
package splice.server

import splice.core.topology.ServerLimits
import splice.core.topology.ServerTeams
import splice.core.wire.HttpStatus
import splice.server.load.ServerLoad
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One admitted turn's places in flight, among its [person]'s turns and the whole [server]'s (ServerLoad), every one
 *  given back once however often [release] is called. */
internal class TurnSlot(private val person: PersonUse? = null, private val server: ServerLoad.Place? = null) {
    private val released = AtomicBoolean()

    fun release() {
        if (released.compareAndSet(false, true)) {
            person?.ended()
            server?.giveBack()
        }
    }

    /** One slot holding this one's places and [other]'s, given back together. Neither is released apart from it. */
    fun joined(other: TurnSlot): TurnSlot = TurnSlot(person ?: other.person, server ?: other.server)
}

/** What asking to start a turn came to. */
internal sealed class LimitVerdict {
    class Admitted(val slot: TurnSlot) : LimitVerdict()

    /** Refused with [status]: [why] names the cap, and the person may try again in [retryAfterSeconds]. */
    data class Refused(
        val why: String,
        val retryAfterSeconds: Long,
        val status: Int = HttpStatus.TOO_MANY_REQUESTS,
    ) : LimitVerdict()
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
        val wait = starts.waitFor(rpmCap.toLong(), now)
        if (wait != null) {
            val why = "$person started $rpmCap turns in the last minute, the most one person may start here " +
                "(rpm_per_person = $rpmCap); the next can start in $wait s"
            return LimitVerdict.Refused(why, wait)
        }
        inflight++
        starts.add(now)
        return LimitVerdict.Admitted(TurnSlot(person = this))
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
    /** `[server.teams]`: whose team's commands hold a person with no commands of their own. */
    teams: ServerTeams,
    private val clock: Clock = Clock.systemUTC(),
    /** What the whole server may hold, everyone's turns together, and its drain at stop (#791). */
    val load: ServerLoad,
) {
    private val uses = ConcurrentHashMap<String, PersonUse>()

    /** Which commands each person may use (F12): their own setting, else their team's, else every one. */
    val access: CommandAccess = CommandAccess(people, teams)

    /** Starts a turn for [person], or refuses it. An admitted turn's slot must be released when the turn ends. */
    fun begin(person: String): LimitVerdict =
        uses.computeIfAbsent(person) { PersonUse() }.begin(person, capsOf(person), clock.millis())

    /** How many of [person]'s turns are in flight now (F6). */
    fun inflight(person: String): Int = uses[person]?.held() ?: 0

    /** The most turns [person] may hold in flight at once; null when nothing caps them (F6). */
    fun inflightCap(person: String): Int? = capsOf(person).inflightPerPerson.takeIf { it > 0 }

    /** The most tokens [person]'s turns that ended in the last minute may use, and how the refusal names that cap;
     *  null when nothing caps them (F12). */
    fun tokenCap(person: String): TokenCap? {
        val own = people.of(person).tpm
        return when {
            own != null -> TokenCap(own.toLong(), "their own cap, set with --tpm")
            server.tpmPerPerson > 0 -> TokenCap(server.tpmPerPerson.toLong(), "tpm_per_person = ${server.tpmPerPerson}")
            else -> null
        }
    }

    /** [person]'s caps: their own where the operator set one, else the server's. */
    private fun capsOf(person: String): ServerLimits {
        val own = people.of(person)
        return ServerLimits(own.inflight ?: server.inflightPerPerson, own.rpm ?: server.rpmPerPerson)
    }
}

// why: nothing says when a turn in flight will end, a short answer in a second or a long one in minutes. One second
// is the shortest wait Retry-After can name, and Claude Code backs off further on its own while the refusals repeat.
private const val INFLIGHT_RETRY_SECONDS = 1L

/** A person's token cap (F12): [tokens] a minute, as the refusal [named] it. */
internal data class TokenCap(val tokens: Long, val named: String)
