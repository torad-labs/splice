// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). A request the relay sends to a server that is no
// head of this machine, such as a provider's own API, as the listener that relays it describes it (ShareRelay.outward).
// A head decides what leaves the machine for the turns it is handed; such a server is already outside it, so the
// listener says here, header by header, what of the caller's request rides on.
//
// A provider may be several servers, and which of them a request is asked of is the listener's to say as well, one
// server at a time (OutwardWay): the relay asks the next only when the one before took no connection, and it does so
// wherever the request stands by then, behind a stream it has already opened to keep its client alive included.
package splice.share

import java.net.URI
import kotlin.time.Duration

/** One request for a server that is no head of this machine, whichever server takes it: its [body], the headers it
 *  carries, what reads the events of a streamed answer on their way back ([edit]; null: byte for byte), what reads an
 *  answer that is no stream ([whole]; null: it goes back as it is), and how often its client is sent a [keepalive]
 *  while a streamed answer is silent (null: never, and the client waits on the server). */
public data class OutwardAsk(
    val body: ByteArray,
    val headers: OutwardHeaders,
    val edit: StreamEdit? = null,
    val whole: WholeEdit? = null,
    val keepalive: Duration? = null,
)

/** What a request to a server that is no head of this machine carries: of the caller's own headers the ones named
 *  in [carried], lowercase, as the caller sent them, and then the server's [own], each in place of whatever the
 *  caller sent under its name. Every other header the caller sent stays on this machine. */
public data class OutwardHeaders(val carried: Set<String>, val own: Map<String, String>)

/** One server a request is asked of: where the request goes there ([target]), and how its client hears the server
 *  named when it gives no answer or breaks one off (`upstream gpu-1 of box`). */
public data class OutwardServer(val target: URI, val name: String)

/** One request's way through the servers that may answer it. */
public interface OutwardWay {
    /** The server to ask now: the first, and after it the one that follows a server that took no connection, which
     *  got nothing of the request. Null once none is left to ask. */
    public fun next(): OutwardServer?

    /** What the client is told once no server took the request. */
    public fun noneTook(): String
}
