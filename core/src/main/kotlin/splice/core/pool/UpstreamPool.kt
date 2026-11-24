// NEW: server mode (docs/specs/server-mode.md, F4). One provider's servers as a turn sees them. A provider that lists
// `[[providers.<p>.upstreams]]` is several machines behind one API, and this is the one place that knows which of them
// can take a turn now and how many each is answering:
//
//   - a turn takes a lease on the healthy server with the least in flight, the first listed among equals, or on the
//     one its person prefers while that one is healthy, and gives the lease back when it ends;
//   - a Claude Code session's turn goes back to the server that served its last one while that one is healthy (F11),
//     however busy, since its prompt is cached there; a session whose server went down moves once, and its new
//     server is its home from then, the old one back or not;
//   - with server mode's `inflight_per_upstream` (#791), a server that holds that many takes no more while another
//     healthy one has room: a session's server that is full gives the turn to the least busy healthy one with room,
//     and stays the session's home for its next turn, and a person's preferred one that is full gives a new session's
//     turn to the least busy with room. Server mode reserves a place for each turn it admits ([UpstreamPool.reserve]),
//     counted against the servers' room together with every lease, so the turns it admitted before any of them leased
//     never add up past the cap; the turn's first lease takes the place, and its end gives back one never taken;
//   - a health check that fails takes a server out, and so does a connection it refuses, at once and before the next
//     check; only a health check that passes brings it back;
//   - only a legacy health-checked pool without a cap still gives a lease when none is healthy, so whoever sends a
//     turn anyway hears the server's own answer. Completion-checked pools never give a lease on a down server.
//
// A server starts healthy, so a daemon that just booted serves before its first check lands; the first check and the
// first refused connection correct that within moments. Where a completion probe holds each server out until one passes
// (`[server.probe] at_start`), a server starts down instead, and its first failed check says why in the log. The pool
// holds no network: what checks health tells it ([UpstreamPool.checked]), and what sends a turn tells it
// ([UpstreamLease.refused]).
package splice.core.pool

import splice.core.failure.Decline
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink

/** One server of a pool as it reads now: what the operator's list of upstreams shows. */
public data class UpstreamReading(
    val provider: String,
    val name: String,
    val url: String,
    val healthy: Boolean,
    val inflight: Int,
)

/** One server's state, read and changed only under its pool's lock. */
internal class UpstreamMember(val config: UpstreamConfig, var healthy: Boolean) {
    /** Held out from the start and not yet passed a check, so it has never taken a turn. */
    var held: Boolean = !healthy

    /** Whether a check of it has come back yet. */
    var asked: Boolean = false
    var inflight: Int = 0

    fun reading(provider: String): UpstreamReading =
        UpstreamReading(provider, config.name, config.url, healthy, inflight)
}

public class UpstreamPool(
    /** The provider these servers are, by its key in splice.toml. */
    public val provider: String,
    upstreams: List<UpstreamConfig>,
    private val log: LogSink,
    /** The most turns each server takes at once, server mode's `inflight_per_upstream` (#791), held from the pool's
     *  first lease; 0 takes any number. */
    cap: Int = 0,
    /** Whether a server takes turns before its first check answers: true, as server mode always did, unless a
     *  completion probe holds each one out until it passes. */
    startHealthy: Boolean = true,
    /** Whether an uncapped pool may still try down servers when none is up, for legacy health checks only. */
    private val allowUnhealthyFallback: Boolean = startHealthy,
) {
    private val members = upstreams.map { UpstreamMember(it, startHealthy) }
    private val homes = SessionHomes()

    // The most turns each server takes at once (server mode's `inflight_per_upstream`); 0 takes any number.
    private var each = cap

    // The turns server mode admitted that have not leased yet, by the id it named each by: each holds a place.
    private val reservations = HashSet<String>()

    /** The servers as the file lists them: their names, and where each is asked for its health. */
    public val upstreams: List<UpstreamConfig> = upstreams.toList()

    /** Whether any server can take a turn. */
    public val up: Boolean
        @Synchronized get() = members.any { it.healthy }

    /** Every server as it reads now, in the file's order. */
    @Synchronized
    public fun readings(): List<UpstreamReading> = members.map { it.reading(provider) }

    /** A place for the turn server mode names [turn], with each server taking at most [most] turns at once from now
     *  (`inflight_per_upstream`, #791): true when the healthy servers' room, less the places already reserved, has one
     *  left, which is then this turn's until its first lease ([lease]) or [unreserve]. False refuses the turn. With
     *  none healthy the turn is not the cap's to refuse: server mode's own 503 naming the upstreams says so. */
    @Synchronized
    public fun reserve(turn: String, most: Int): Boolean {
        each = most
        val healthy = members.filter { it.healthy }
        if (healthy.isNotEmpty() && room(healthy) <= reservations.size) return false
        reservations += turn
        return true
    }

    /** The turn named [turn] ended: a place it reserved and never leased is free again. */
    @Synchronized
    public fun unreserve(turn: String) {
        reservations -= turn
    }

    /** A place on the server a turn goes to now: the one that served [session]'s last turn while it is healthy, else
     *  [preferred] while a server of that name is healthy, else the healthy one with the least in flight. Only legacy
     *  health checks allow an uncapped pool with no healthy server to try a down one. Under a cap a session whose home
     *  is full goes to the least busy healthy server with room and keeps its home, since its prompt is cached
     *  there. A [turn] server mode reserved a place for takes it here; any other lease takes a place only where one is
     *  left beside the reserved ones. Under a cap no lease is ever given on a server that is full or down: with no place
     *  left, null, and the turn is sent nowhere ([full] says why to its person while one is up). A healthy server
     *  chosen for a [session] with no healthy home is where its next turn goes. The turn counts as in flight there until
     *  the lease is released. */
    @Synchronized
    public fun lease(preferred: String? = null, session: String? = null, turn: String? = null): UpstreamLease? {
        val reserved = turn != null && reservations.remove(turn)
        val healthy = members.filter { it.healthy }
        val open = open(healthy, reserved) ?: return null
        val home = homes.of(session)?.let { name -> healthy.firstOrNull { it.config.name == name } }
        val chosen = choose(open, home, preferred)
        // A session with no healthy home makes its home where its turn went; one whose home is only full keeps it.
        val moves = home == null && chosen.healthy
        if (session != null && moves) homes.served(session, chosen.config.name)
        chosen.inflight++
        return UpstreamLease(this, chosen)
    }

    /** The server of [open] a turn goes to: its session's healthy [home] while that one has room, the least busy when
     *  it is full, and for a turn with no home [preferred] while that one has room, else the least busy. */
    private fun choose(open: List<UpstreamMember>, home: UpstreamMember?, preferred: String?): UpstreamMember = when {
        home != null && home in open -> home
        home != null -> open.minBy { it.inflight }
        else -> open.firstOrNull { it.config.name == preferred } ?: open.minBy { it.inflight }
    }

    /** Healthy servers with room, or all servers if the uncapped legacy fallback applies; null if no lease is possible. */
    private fun open(healthy: List<UpstreamMember>, reserved: Boolean): List<UpstreamMember>? {
        if (each <= 0) {
            val available = if (healthy.isEmpty() && allowUnhealthyFallback) members else healthy
            return available.takeIf { it.isNotEmpty() }
        }
        val roomy = healthy.filter { it.inflight < each }
        val spare = reserved || room(healthy) > reservations.size
        return roomy.takeIf { spare && it.isNotEmpty() }
    }

    /** What a turn [lease] found no place for is told: every server that could take it holds the cap's number. */
    @Synchronized
    public fun full(): Decline.UpstreamsFull = Decline.UpstreamsFull(provider, each)

    /** How many more turns [servers] take under the cap, in Long: a cap near Int.MAX_VALUE times the servers would
     *  wrap negative in Int and leave no room at all. */
    private fun room(servers: List<UpstreamMember>): Long =
        servers.sumOf { (each - it.inflight).coerceAtLeast(0).toLong() }

    /** What a health check of the server [name] found; [said] is how it answered, for the log. */
    @Synchronized
    public fun checked(name: String, healthy: Boolean, said: String) {
        val member = members.firstOrNull { it.config.name == name } ?: return
        val first = !member.asked
        member.asked = true
        if (healthy) {
            val again = if (member.held) "" else " again"
            if (!member.healthy) log("[$provider] upstream $name passed its health check; it takes turns$again\n")
            member.healthy = true
            member.held = false
        } else {
            // A server held out from the start says why at its first check, as one taken out does when it goes down.
            down(member, "failed its health check ($said)", loud = member.healthy || member.held && first)
        }
    }

    @Synchronized
    internal fun refused(member: UpstreamMember) {
        down(member, "took no connection")
    }

    @Synchronized
    internal fun released(member: UpstreamMember) {
        member.inflight--
    }

    @Synchronized
    internal fun healthy(member: UpstreamMember): Boolean = member.healthy

    private fun down(member: UpstreamMember, why: String, loud: Boolean = member.healthy) {
        if (loud) {
            val others = if (members.any { it !== member && it.healthy }) "the others take its turns" else "none is up"
            log("[$provider] upstream ${member.config.name} $why; $others until it passes a health check\n")
        }
        member.healthy = false
    }
}

/** One turn's place on one server of a pool, given back once however often [release] is called. */
public class UpstreamLease internal constructor(private val pool: UpstreamPool, private val member: UpstreamMember) {
    private var released = false

    public val name: String get() = member.config.name

    /** The server's API root, without a trailing slash. */
    public val url: String get() = member.config.base

    /** Whether the server still reads healthy. */
    public val healthy: Boolean get() = pool.healthy(member)

    /** The server took no connection for this turn's request, so nothing of the request reached it: it reads
     *  unhealthy from now, for every turn, until a health check passes. */
    public fun refused() {
        pool.refused(member)
    }

    @Synchronized
    public fun release() {
        if (!released) pool.released(member)
        released = true
    }
}

/** The header a turn handed to a head names its person's preferred upstream in. The listeners that relay other
 *  machines' turns set it themselves and never pass on one a caller sent. */
public const val PREFERRED_UPSTREAM_HEADER: String = "x-splice-upstream"
