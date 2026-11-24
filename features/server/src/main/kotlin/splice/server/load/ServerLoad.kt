// NEW: server mode (#791, load and drain). What the whole server may hold at once, beside what each person may
// (PersonLimits): a small fixed pool of model servers takes so many turns and no more, whoever sends them. Two caps,
// each 0 for none: `[server.limits] inflight_total`, the server's turns in flight together; and
// `inflight_per_upstream`, the turns each server of a provider that is several may hold, which refuses a turn on such
// a provider once its healthy servers are full. A turn past either is refused at once, never queued, with
// `busy_status` (429 or 503), `Retry-After: busy_retry_after` and `busy_message` or splice's own sentence, and it
// counts toward neither. Its place is given back with its person's, through the one slot the turn holds (TurnSlot).
//
// It is also where a stop's drain is said (`[server] drain_seconds`): from the moment the listener starts to stop,
// every new turn is refused with a 503 and `Retry-After`, `/health` answers that the server is draining, and the stop
// waits for the turns in flight here to end, up to the drain's length, before it closes the listener.
package splice.server.load

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.pool.UpstreamPool
import splice.core.topology.ServerLimits
import splice.core.wire.HttpStatus
import splice.server.HeldPlace
import splice.server.LimitVerdict
import splice.server.TurnSlot
import splice.server.UpstreamApi

internal class ServerLoad(private val limits: ServerLimits, private val upstreams: UpstreamApi) {
    // Every admitted turn in flight, the server's together; read by a drain, which waits for it to reach zero.
    private val held = MutableStateFlow(0)

    // The admitted turns in flight on each provider that is several servers, by its key.
    private val onPool = HashMap<String, Int>()

    /** Set once the listener starts to stop: from then on every new turn is refused. */
    @Volatile
    var draining: Boolean = false
        private set

    /** Starts a turn on the command [key] names, or says how the server's caps refuse it. An admitted turn's place
     *  must be given back when the turn ends, through the slot it holds. */
    @Synchronized
    fun begin(key: String): LimitVerdict {
        // Asked under the lock: a turn either is counted before a drain begins, or is refused by it.
        val pool = upstreams.pool(key)?.takeIf { limits.inflightPerUpstream > 0 }
        val refused = drainRefusal() ?: overTotal() ?: pool?.let(::overPool)
        if (refused != null) return refused
        held.value += 1
        val provider = pool?.provider
        provider?.let { onPool[it] = (onPool[it] ?: 0) + 1 }
        return LimitVerdict.Admitted(TurnSlot(listOf(HeldPlace { ended(provider) })))
    }

    /** Why the next turn is past `inflight_total`; null while it is under it, or nothing caps it. */
    private fun overTotal(): LimitVerdict.Refused? {
        val total = limits.inflightTotal
        if (total <= 0 || held.value < total) return null
        return busy(
            "this server already has ${held.value} turns in flight, the most it takes at once " +
                "(inflight_total = $total); the next can start when one of them ends",
        )
    }

    /** Why the next turn on [pool] is past `inflight_per_upstream`: every healthy server of it is full. Ours counts the
     *  turns admitted here that their head has not yet placed on a server; the readings count every lease. With none
     *  healthy the turn is not this cap's to refuse: the server's own 503 naming the upstreams has said so already. */
    private fun overPool(pool: UpstreamPool): LimitVerdict.Refused? {
        val each = limits.inflightPerUpstream
        val healthy = pool.readings().filter { it.healthy }
        val ours = onPool[pool.provider] ?: 0
        val full = healthy.isNotEmpty() && (ours >= each * healthy.size || healthy.all { it.inflight >= each })
        if (!full) return null
        return busy(
            "every upstream of ${pool.provider} (${healthy.joinToString(", ") { it.name }}) already has $each turns " +
                "in flight, the most each takes at once (inflight_per_upstream = $each); the next can start when one " +
                "of them ends",
        )
    }

    /** Why a new turn is refused while the server drains, and how long its client waits; null while it serves. */
    fun drainRefusal(): LimitVerdict.Refused? = LimitVerdict.Refused(
        "this server is stopping: it takes no new turn while the ones in flight finish; try again in " +
            "${limits.busyRetryAfter} s",
        limits.busyRetryAfter.toLong(),
        HttpStatus.SERVICE_UNAVAILABLE,
    ).takeIf { draining }

    /** Refuses every new turn from now, and waits up to [withinMs] for the turns in flight to end. Answers whether
     *  they all did. */
    suspend fun drain(withinMs: Long): Boolean {
        refuseNew()
        return withTimeoutOrNull(withinMs) { held.first { it == 0 } } != null
    }

    @Synchronized
    private fun refuseNew() {
        draining = true
    }

    /** How many turns the server has in flight now. */
    fun inflight(): Int = held.value

    @Synchronized
    private fun ended(provider: String?) {
        held.value -= 1
        provider?.let { onPool[it] = (onPool[it] ?: 1) - 1 }
    }

    /** A turn refused for a cap of the server's, as `[server.limits]` says such a refusal is answered. */
    private fun busy(why: String): LimitVerdict.Refused =
        LimitVerdict.Refused(limits.busyMessage.ifBlank { why }, limits.busyRetryAfter.toLong(), limits.busyStatus)
}
