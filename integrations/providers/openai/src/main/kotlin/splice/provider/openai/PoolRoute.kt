// NEW: server mode (docs/specs/server-mode.md, F4). One turn's place among its provider's servers. A provider that
// lists `[[providers.<p>.upstreams]]` is several machines, and a turn stays on the one it took (its prompt cache is
// there) unless that one takes no connection: then nothing of the request reached it, the pool reads it down for every
// turn, and the same request goes to another server at once.
package splice.provider.openai

import splice.core.pool.UpstreamLease
import splice.core.pool.UpstreamPool
import splice.core.turn.Usage
import splice.upstream.Reroute
import splice.upstream.RoundBody
import splice.upstream.VendorRoute
import splice.upstream.failure.FailedReply

/** A turn's route into [pool]: on [preferred] while a server of that name is healthy, and otherwise on the healthy
 *  server with the least in flight. The turn counts as in flight on its server from its first request until it ends.
 *  [path] is the route its requests are sent to under the server's API root. */
internal class PoolRoute(
    private val pool: UpstreamPool,
    private val preferred: String?,
    private val path: String,
) : VendorRoute {
    private var lease: UpstreamLease? = null
    private var ended = false

    // Servers this turn left because they took no connection: one pass over the pool at most, so servers whose
    // health check passes while they refuse turns cannot hand a turn round between them without end.
    private var left = 0

    @Synchronized
    override fun target(): String = held().url + path

    @Synchronized
    override fun unreachable(): Reroute {
        val refused = held()
        refused.refused()
        refused.release()
        lease = null
        left++
        return if (pool.up && left < pool.upstreams.size) Reroute.MOVED else Reroute.NOWHERE
    }

    /** The turn's place, taken when its first request asks where to go. A request sent after the turn ended holds
     *  none: it is counted nowhere, since nothing would ever give its place back. */
    private fun held(): UpstreamLease = lease ?: pool.lease(preferred).also { taken ->
        if (ended) taken.release() else lease = taken
    }

    override fun sending(): Map<String, String> = emptyMap()

    override fun body(round: RoundBody): RoundBody? = null

    override fun written(): Unit = Unit

    override fun headersArrived(): Unit = Unit

    override fun answered(reply: FailedReply): Unit = Unit

    override fun finished(usage: Usage?): Unit = Unit

    @Synchronized
    override fun turnEnded() {
        ended = true
        lease?.release()
        lease = null
    }
}
