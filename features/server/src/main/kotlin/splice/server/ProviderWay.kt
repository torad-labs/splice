// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). One OpenAI-style request's way through the servers
// of its command's provider, as the relay asks for them one at a time (OutwardWay). A provider that is one server is
// asked there, once. One that is several is asked on the server its pool gives the person (their preferred one while
// it is healthy, else the healthy one with the least in flight), and when that one takes no connection, on the next,
// the way a head's own turn moves on (PoolWalk); while none of them is up, none is asked. A request no server took is
// answered naming the provider's upstreams, when it is their being down that left none to ask.
package splice.server

import splice.core.pool.PoolWalk
import splice.share.OutwardServer
import splice.share.OutwardWay
import splice.share.SharedHead
import java.net.URI

/** The way of one request for [path], which follows a server's API root, to the provider of [head], whose one
 *  server's API root is [base] when it has no pool in [upstreams]. In a pool it goes first to the server named
 *  [preferred], while that one is healthy. */
internal class ProviderWay(
    private val head: SharedHead,
    private val path: String,
    private val base: String,
    private val upstreams: UpstreamApi,
    preferred: String?,
) : OutwardWay {
    private val pool = upstreams.pool(head.key)
    private val walk = pool?.let { PoolWalk(it, preferred) }

    // How the client hears a provider named that is one server.
    private val alone = "the model server behind ${head.command}"

    private var asked = false

    override fun next(): OutwardServer? {
        val first = !asked
        asked = true
        if (pool == null || walk == null) return OutwardServer(URI.create(base + path), alone).takeIf { first }
        val left = if (first) pool.up else walk.moved()
        if (!left) return null
        val server = walk.server()
        return OutwardServer(URI.create(server.url + path), "upstream ${server.name} of ${pool.provider}")
    }

    override fun noneTook(): String = upstreams.noneUp(head.key) ?: "$alone is not answering"

    /** The request is over, however it ended: its place in the pool is given back. */
    fun end() {
        walk?.end()
    }
}
