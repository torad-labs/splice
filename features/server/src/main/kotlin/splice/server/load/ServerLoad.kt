// NEW: server mode (#791, load and drain). What the whole server may hold at once, beside what each person may
// (PersonLimits): a small fixed pool of model servers takes so many turns and no more, whoever sends them. Two caps,
// each 0 for none: `[server.limits] inflight_total`, the server's turns in flight together; and
// `inflight_per_upstream`, the turns each server of a provider that is several may hold, which refuses a turn on such
// a provider once its healthy servers have no place left, every lease and every place reserved for a turn admitted
// before it counted (UpstreamPool.reserve). A turn past either is refused at once, never queued, with
// `busy_status` (429 or 503), `Retry-After: busy_retry_after` and `busy_message` or splice's own sentence, and it
// counts toward neither. Its place is given back with its person's, through the one slot the turn holds (TurnSlot).
//
// It is also where a stop's drain is said (`[server] drain_seconds`): from the moment the listener starts to stop,
// every new turn is refused with a 503 and `Retry-After` and `/health` answers that the server is draining. What the
// stop then waits for is the listener's answers on the wire (AnswersInFlight), which outlast the turns counted here.
package splice.server.load

import splice.core.pool.UpstreamPool
import splice.core.topology.ServerLimits
import splice.core.wire.HttpStatus
import splice.server.LimitVerdict
import splice.server.TurnSlot
import splice.server.UpstreamApi

internal class ServerLoad(private val limits: ServerLimits, private val upstreams: UpstreamApi) {
    // Every admitted turn in flight, the server's together.
    @Volatile
    private var held = 0

    /** Set once the listener starts to stop: from then on every new turn is refused. */
    @Volatile
    var draining: Boolean = false
        private set

    /** Starts the turn named [turn] on the command [key] names, or says how the server's caps refuse it. An admitted
     *  turn's place must be given back when the turn ends, through the slot it holds. */
    @Synchronized
    fun begin(key: String, turn: String): LimitVerdict {
        // Asked under the lock: a turn either is counted before a drain begins, or is refused by it.
        val pool = upstreams.pool(key)?.takeIf { limits.inflightPerUpstream > 0 }
        val refused = drainRefusal() ?: overTotal() ?: pool?.let { overPool(it, turn) }
        if (refused != null) return refused
        held += 1
        return LimitVerdict.Admitted(TurnSlot(server = Place(pool, turn)))
    }

    /** The place the turn named [turn] holds among the server's while it is in flight, and in [pool] the place it
     *  reserved there until it leased. */
    inner class Place(private val pool: UpstreamPool?, private val turn: String) {
        fun giveBack() {
            ended(pool, turn)
        }
    }

    /** Why the next turn is past `inflight_total`; null while it is under it, or nothing caps it. */
    private fun overTotal(): LimitVerdict.Refused? {
        val total = limits.inflightTotal
        if (total <= 0 || held < total) return null
        return busy(
            "this server already has $held turns in flight, the most it takes at once " +
                "(inflight_total = $total); the next can start when one of them ends",
        )
    }

    /** Why the turn named [turn] is past `inflight_per_upstream` on [pool]: its healthy servers have no place left once
     *  every lease and every place reserved for a turn admitted before it are counted. An admitted turn reserves its
     *  place in the pool here, atomically, and its first lease takes it (UpstreamPool.reserve). */
    private fun overPool(pool: UpstreamPool, turn: String): LimitVerdict.Refused? {
        val each = limits.inflightPerUpstream
        if (pool.reserve(turn, each)) return null
        val healthy = pool.readings().filter { it.healthy }
        return busy(
            "every upstream of ${pool.provider} (${healthy.joinToString(", ") { it.name }}) already has $each turns " +
                "in flight, the most each takes at once (inflight_per_upstream = $each); the next can start when one " +
                "of them ends",
        )
    }

    /** Why a new turn is refused while the server drains, and how long its client waits; null while it serves. */
    fun drainRefusal(): LimitVerdict.Refused? = LimitVerdict.Refused(
        "this server is stopping: it takes no new request while the turns in flight finish; try again in " +
            "${limits.busyRetryAfter} s",
        limits.busyRetryAfter.toLong(),
        HttpStatus.SERVICE_UNAVAILABLE,
    ).takeIf { draining }

    /** Refuses every new turn from now: the stop's drain has begun. */
    @Synchronized
    fun refuseNew() {
        draining = true
    }

    /** How many turns the server has in flight now. */
    fun inflight(): Int = held

    /** The turn named [turn] ended: its place among the server's is free, and so is any it reserved in [pool] and
     *  never leased. */
    @Synchronized
    private fun ended(pool: UpstreamPool?, turn: String) {
        held -= 1
        pool?.unreserve(turn)
    }

    /** A turn refused for a cap of the server's, as `[server.limits]` says such a refusal is answered. */
    private fun busy(why: String): LimitVerdict.Refused =
        LimitVerdict.Refused(
            limits.busyMessage.ifBlank { why },
            limits.busyRetryAfter.toLong(),
            limits.busyStatus ?: HttpStatus.TOO_MANY_REQUESTS,
        )
}
