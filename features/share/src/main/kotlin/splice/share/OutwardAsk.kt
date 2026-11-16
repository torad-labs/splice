// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). A request the relay sends to a server that is no
// head of this machine, such as a provider's own API, as the listener that relays it describes it (ShareRelay.outward).
// A head decides what leaves the machine for the turns it is handed; such a server is already outside it, so the
// listener says here, header by header, what of the caller's request rides on.
package splice.share

import java.net.URI

/** One request for a server that is no head of this machine: where it goes, the headers it carries, what its client
 *  hears when the server does not answer ([silent]), and what reads the events of a streamed answer on their way
 *  back ([edit]; null: byte for byte). */
public data class OutwardAsk(
    val target: URI,
    val headers: OutwardHeaders,
    val silent: String,
    val edit: StreamEdit? = null,
)

/** What a request to a server that is no head of this machine carries: of the caller's own headers the ones named
 *  in [carried], lowercase, as the caller sent them, and then the server's [own], each in place of whatever the
 *  caller sent under its name. Every other header the caller sent stays on this machine. */
public data class OutwardHeaders(val carried: Set<String>, val own: Map<String, String>)
