// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). One OpenAI-style request's way through the servers
// of its command's provider, as the relay asks for them one at a time (OutwardWay). A provider that is one server is
// asked there, once. One that is several is asked on the server its pool gives the person (their preferred one while
// it is healthy, else the healthy one with the least in flight), and when that one takes no connection, on the next,
// the way a head's own turn moves on (PoolWalk); while none of them is up, none is asked. A request no server took is
// answered naming the provider's upstreams, when it is their being down that left none to ask.
package splice.server

import splice.core.failure.Decline
import splice.core.pool.PoolWalk
import splice.share.OutwardServer
import splice.share.OutwardWay
import splice.share.SharedHead
import java.net.URI

/** The way of one request for [path], which follows a server's API root, to the provider of [head], whose one
 *  server's API root is [base] when it has no pool in [upstreams]. In a pool it goes first to the server named
 *  [preferred], while that one is healthy, and a completion admitted as [turn] takes the place reserved for it. */
internal class ProviderWay(
    private val head: SharedHead,
    private val path: String,
    private val base: String,
    private val upstreams: UpstreamApi,
    preferred: String?,
    turn: String? = null,
) : OutwardWay {
    private val pool = upstreams.pool(head.key)
    private val walk = pool?.let { PoolWalk(it, preferred, turn = turn) }

    // How the client hears a provider named that is one server.
    private val alone = "the model server behind ${head.command}"

    private var asked = false

    // Why the request was sent nowhere: every server of the pool full under its cap; null while it found one.
    private var full: Decline.UpstreamsFull? = null

    override fun next(): OutwardServer? {
        val first = !asked
        asked = true
        if (pool == null || walk == null) return OutwardServer(URI.create(base + path), alone).takeIf { first }
        val left = if (first) pool.up else walk.moved()
        val server = if (left) walk.server() else null
        // A server left to ask that the cap gives no place on: every one is full, and none is asked (#791).
        if (left && server == null) full = pool.full()
        return server?.let { OutwardServer(URI.create(it.url + path), "upstream ${it.name} of ${pool.provider}") }
    }

    override fun noneTook(): String = full?.let(::busy) ?: upstreams.noneUp(head.key) ?: "$alone is not answering"

    /** What a request no server had a place for is told, [full] under the cap (#791): no server was asked. */
    private fun busy(full: Decline.UpstreamsFull): String =
        "every upstream of ${full.provider} already holds ${full.each} turn(s) in flight, the most each takes at " +
            "once (inflight_per_upstream = ${full.each}); try again when one of them ends"

    /** The upstream of the pool that took the request, or null for a provider that is one server. */
    fun served(): String? = walk?.taken

    /** The request is over, however it ended: its place in the pool is given back. */
    fun end() {
        walk?.end()
    }
}
