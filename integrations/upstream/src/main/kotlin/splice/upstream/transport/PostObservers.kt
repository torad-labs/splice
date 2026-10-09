package splice.upstream.transport

import splice.core.perf.TurnPerf
import splice.upstream.sse.WireObserver

/** What listens to a post without changing it: the turn's timing row, the wire recorder and the refresh observer. */
public data class PostObservers(
    val perf: TurnPerf? = null,
    /** V4-174: hears every send of this post after it ends (headers redacted, body exact). Null —
     *  the default and every head that did not opt in — records nothing and allocates nothing. */
    val wire: WireObserver? = null,
    val authRefreshObserver: AuthRefreshObserver = AuthRefreshObserver {},
)
