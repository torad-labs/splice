// NEW: LAN share, round 11 of PR #419's review. A sharing machine's address as a person types it, to `splice remote
// add` or in the Sharing page's box: one reading for both, so the page takes every form the CLI takes (the page split
// at the last colon, which cut an IPv6 address in two).
package splice.app.cli.share

import splice.core.topology.DEFAULT_SHARE_PORT
import splice.core.util.Cancellables
import java.net.URI

internal object RemoteAddress {
    /** [target] as the host and port to ask: `host`, `host:port`, `[v6]`, `[v6]:port`, or a bare IPv6 address, whose
     *  colons are all its own, read as a URI's authority. The host comes back without brackets, which RemoteHttp.url
     *  puts back once (PR #247's fourth review: `[fd00::1]:3097` was bracketed twice). Null for any other target. */
    fun of(target: String): Pair<String, Int>? {
        val bare = target.count { it == ':' } > 1 && !target.startsWith("[")
        // A target that is no URI authority at all is refused, as one with a path or a bad port is.
        val uri = Cancellables.runCatchingCancellable { URI.create("http://${if (bare) "[$target]" else target}") }
            .fold(onSuccess = { it }, onFailure = { null })
        val host = uri?.takeIf { it.rawPath.isEmpty() }?.host?.removeSurrounding("[", "]")
        val port = uri?.port?.takeIf { it != -1 } ?: DEFAULT_SHARE_PORT
        return host?.takeIf { it.isNotBlank() && port in 1..UShort.MAX_VALUE.toInt() }?.let { it to port }
    }
}
