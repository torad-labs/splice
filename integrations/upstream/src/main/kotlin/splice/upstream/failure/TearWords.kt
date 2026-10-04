// NEW: V4-242 (2026-09-26) — a torn stream named in the words of what tore it.
//
// A transport tears a stream with its own sentence ("websocket stream ended mid-round") and hangs the
// cause beneath it. After a websocket peer's close, that cause is the close itself: its code, the
// peer's reason and the events the round had received. So the words worth showing are the deepest
// link that says anything. The translator's truncated ending and the websocket overlay's bypass line
// both name a tear, and they read it here, once: the two used to walk the chain each with its own
// depth bound, a second copy of one reading.
package splice.upstream.failure

import splice.upstream.transport.FailureChain

public object TearWords {

    /** The deepest non-blank message in [tear]'s cause chain (the chain's own bound, FailureChain), or
     *  null when no link says anything. A URL in it keeps its scheme and host only: an HTTP client's
     *  timeout text carries the whole request URL, and a path or query can carry a key
     *  (TransportFailureReason keeps the same rule for the endpoint it names). */
    public fun of(tear: Throwable): String? =
        FailureChain.links(tear)
            .mapNotNull { link -> link.message?.trim()?.takeIf { it.isNotEmpty() } }
            .lastOrNull()
            ?.replace(urlPastHost, "$1")

    private val urlPastHost = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#\s,\]]+)[^\s,\]]*""")
}
