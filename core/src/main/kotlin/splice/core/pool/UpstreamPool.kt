// NEW: server mode (docs/specs/server-mode.md, F4). One provider's servers as a turn sees them. A provider that lists
// `[[providers.<p>.upstreams]]` is several machines behind one API, and this is the one place that knows which of them
// can take a turn now and how many each is answering:
//
//   - a turn takes a lease on the healthy server with the least in flight, the first listed among equals, or on the
//     one its person prefers while that one is healthy, and gives the lease back when it ends;
//   - a Claude Code session's turn goes back to the server that served its last one while that one is healthy (F11),
//     however busy, since its prompt is cached there; a session whose server went down moves once, and its new
//     server is its home from then, the old one back or not;
//   - a health check that fails takes a server out, and so does a connection it refuses, at once and before the next
//     check; only a health check that passes brings it back;
//   - with none healthy a lease is still given, on the least busy of them all: whoever sends a turn anyway then hears
//     the server's own answer. The server listener asks [UpstreamPool.up] first and refuses the turn itself.
//
// A server starts healthy, so a daemon that just booted serves before its first check lands; the first check and the
// first refused connection correct that within moments. The pool holds no network: what checks health tells it
// ([UpstreamPool.checked]), and what sends a turn tells it ([UpstreamLease.refused]).
package splice.core.pool

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
internal class UpstreamMember(val config: UpstreamConfig) {
    var healthy: Boolean = true
    var inflight: Int = 0

    fun reading(provider: String): UpstreamReading =
        UpstreamReading(provider, config.name, config.url, healthy, inflight)
}

public class UpstreamPool(
    /** The provider these servers are, by its key in splice.toml. */
    public val provider: String,
    upstreams: List<UpstreamConfig>,
    private val log: LogSink,
) {
    private val members = upstreams.map(::UpstreamMember)
    private val homes = SessionHomes()

    /** The servers as the file lists them: their names, and where each is asked for its health. */
    public val upstreams: List<UpstreamConfig> = upstreams.toList()

    /** Whether any server can take a turn. */
    public val up: Boolean
        @Synchronized get() = members.any { it.healthy }

    /** Every server as it reads now, in the file's order. */
    @Synchronized
    public fun readings(): List<UpstreamReading> = members.map { it.reading(provider) }

    /** A place on the server a turn goes to now: the one that served [session]'s last turn while it is healthy, else
     *  [preferred] while a server of that name is healthy, else the healthy one with the least in flight; with none
     *  healthy, the least busy of them all. A healthy server chosen for [session] is where its next turn goes. The
     *  turn counts as in flight there until the lease is released. */
    @Synchronized
    public fun lease(preferred: String? = null, session: String? = null): UpstreamLease {
        val healthy = members.filter { it.healthy }
        val home = session?.let(homes::of)
        val chosen = healthy.firstOrNull { it.config.name == home }
            ?: healthy.firstOrNull { it.config.name == preferred }
            ?: healthy.ifEmpty { members }.minBy { it.inflight }
        if (session != null && chosen.healthy) homes.served(session, chosen.config.name)
        chosen.inflight++
        return UpstreamLease(this, chosen)
    }

    /** What a health check of the server [name] found; [said] is how it answered, for the log. */
    @Synchronized
    public fun checked(name: String, healthy: Boolean, said: String) {
        val member = members.firstOrNull { it.config.name == name } ?: return
        if (healthy) {
            if (!member.healthy) log("[$provider] upstream $name passed its health check; it takes turns again\n")
            member.healthy = true
        } else {
            down(member, "failed its health check ($said)")
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

    private fun down(member: UpstreamMember, why: String) {
        if (member.healthy) {
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
