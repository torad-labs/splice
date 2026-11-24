// NEW: server mode (docs/specs/server-mode.md, F4: "a refused connection fails over at once"). One request's way
// through its provider's servers, the same for every request the daemon sends a pool: a head's turn (PoolRoute) and an
// OpenAI-style client's request the server listener relays (OpenAiRoute). The request stays on the server it took,
// where its prompt cache is, unless that one takes no connection: then nothing of the request reached it, the pool
// reads it down for every request, and the same request goes to another server at once. One pass over the pool at
// most, so servers that pass their health check while they refuse connections cannot hand a request round between
// them without end.
package splice.core.pool

/** A request's place in [pool]: on the server that served [session]'s last turn while it is healthy, else on
 *  [preferred] while a server of that name is healthy, and otherwise on the healthy server with the least in flight.
 *  The request counts as in flight on its server from the first time it asks where to go until it [end]s. A request
 *  server mode admitted is named [turn], and its first lease takes the place server mode reserved for it. */
public class PoolWalk(
    private val pool: UpstreamPool,
    private val preferred: String?,
    private val session: String? = null,
    private val turn: String? = null,
) {
    private var lease: UpstreamLease? = null
    private var ended = false

    /** The name of the server the request last went to, kept once it ended; null before it asked where to go. */
    @Volatile
    public var taken: String? = null
        private set

    // Servers this request left because they took no connection.
    private var left = 0

    /** The server the request goes to now, taken when it is first asked for. A request that asks after it ended
     *  holds no place: it is counted nowhere, since nothing would ever give its place back. */
    @Synchronized
    public fun server(): UpstreamLease = lease ?: pool.lease(preferred, session, turn).also { leased ->
        taken = leased.name
        if (ended) leased.release() else lease = leased
    }

    /** The server the request is on took no connection: it reads down from now and the request's place there is
     *  given back. True when another server is left to ask, which [server] then names; false once none is up or
     *  every server was asked. */
    @Synchronized
    public fun moved(): Boolean {
        val refused = server()
        refused.refused()
        refused.release()
        lease = null
        left++
        return pool.up && left < pool.upstreams.size
    }

    /** The request is over, however it ended: its place is given back. */
    @Synchronized
    public fun end() {
        ended = true
        lease?.release()
        lease = null
    }
}
