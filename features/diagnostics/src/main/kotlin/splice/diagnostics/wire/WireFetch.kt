// NEW: the one network seam of `splice wire` and its daemon-client default, split from WireCommand.kt
// when that file entered concentration band HIGH (LAYOUT-01): the verb's request to a head's own port
// is its own concern, and the only one that names ControlPlaneClient.
package splice.diagnostics.wire

import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply

// why: the 10s budget JdkAddHttp gave this verb before it read through the daemon client; the ring of
// bodies a head serves can be large, and ControlPlaneClient's 3s default was sized for shutdown answers.
private const val WIRE_READ_TIMEOUT_MS = 10_000

/** The one network seam of `splice wire`: a request to the head's own port under the management key,
 *  or null when nothing answers. The method rides along so a test pins the whole request. */
public fun interface WireFetch {
    public fun request(method: String, url: String, bearer: String): ControlReply?
}

/** The real request: the daemon client's loopback call, with the read budget a large ring needs. */
internal class DaemonWireFetch : WireFetch {
    override fun request(method: String, url: String, bearer: String): ControlReply? =
        ControlPlaneClient.send(url, method, bearer, readTimeoutMs = WIRE_READ_TIMEOUT_MS)
}
