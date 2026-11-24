// NEW: server mode (docs/specs/server-mode.md, F4). One turn's place among its provider's servers. A provider that
// lists `[[providers.<p>.upstreams]]` is several machines, and a turn stays on the one it took (its prompt cache is
// there) unless that one takes no connection: then the same request goes to another server at once. Which server, and
// when the turn has none left, is the pool's one walk (PoolWalk), which the server listener's OpenAI route takes too.
package splice.provider.openai

import splice.core.pool.PoolWalk
import splice.core.pool.UpstreamPool
import splice.core.turn.Usage
import splice.upstream.Reroute
import splice.upstream.RoundBody
import splice.upstream.VendorRoute
import splice.upstream.failure.FailedReply

/** A turn's route into [pool]: on the server that served its [session]'s last turn while that one is healthy, else on
 *  [preferred] while a server of that name is healthy, and otherwise on the healthy server with the least in flight.
 *  The turn counts as in flight on its server from its first request until it ends. [path] is the route its requests
 *  are sent to under the server's API root. A turn server mode admitted is [served], the id its listener named it by,
 *  and takes the place server mode reserved for it in the pool. */
internal class PoolRoute(
    private val pool: UpstreamPool,
    preferred: String?,
    session: String?,
    private val path: String,
    served: String? = null,
) : VendorRoute {
    private val walk = PoolWalk(pool, preferred, session, served)

    override fun target(): String = walk.server().url + path

    override fun unreachable(): Reroute = if (walk.moved()) Reroute.MOVED else Reroute.NOWHERE

    override fun places(): List<String> = pool.upstreams.map { it.name }

    override fun served(): String? = walk.taken

    override fun sending(): Map<String, String> = emptyMap()

    override fun body(round: RoundBody): RoundBody? = null

    override fun written(): Unit = Unit

    override fun headersArrived(): Unit = Unit

    override fun answered(reply: FailedReply): Unit = Unit

    override fun finished(usage: Usage?): Unit = Unit

    override fun turnEnded() {
        walk.end()
    }
}
