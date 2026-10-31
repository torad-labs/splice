// NEW: where a vendor routes one turn's requests, asked per attempt (Oct 26, 2026). Muse's gateway sends a
// conversation's requests to one place by the prompt_cache_key in their body, with a header splice sends beside it,
// and the conversation's prompt cache lives there. A request sent to that place while a generation is still running
// there waits behind it: on Oct 26 a re-send after Meta's gateway gave up on a generation, and the four requests after
// it, waited 18 minutes. The transport and the head only report what each request met and send what the vendor routes
// by, and the vendor decides when its conversation leaves that place, the way a vendor's reading of its own
// replies lives in its own module (ProviderDeviation).
package splice.upstream

import splice.core.turn.Usage
import splice.upstream.failure.FailedReply

/** One turn's route upstream, from [Provider.route]. The transport and the head say what happened to each request it
 *  routed; which of those means the conversation must leave its route is the vendor's to know. */
public interface VendorRoute {
    /** The headers that route the request about to be sent. */
    public fun sending(): Map<String, String>

    /** The body of the request about to be sent, for the place [sending] just chose. Asked once per attempt, right
     *  after [sending], with the body as it was built: null sends [round] as it is, and a copy of [round] carries what
     *  the vendor routes by in its place when that moved after [round] was built. */
    public fun body(round: RoundBody): RoundBody?

    /** The request went out whole. From here it may be running upstream until [answered] or [finished] hears how it
     *  ended, and one sent after it is routed knowing whether it did. A request that failed before this never reached
     *  the upstream whole, so nothing of it runs there. Called on the transport's thread, possibly after [turnEnded]
     *  when the turn ended while the request's last bytes went out. */
    public fun written()

    /** The upstream's response headers for the request arrived, after [written] when it went out whole. Called on the
     *  transport's thread, again for each later response to the same request, such as a redirect's. */
    public fun headersArrived()

    /** The upstream answered the request with [reply] instead of a stream, as its reader was handed it: the same
     *  reply, read once (failure-handling slice 3). Whether that answer ended what the request started upstream is
     *  the vendor's to know. */
    public fun answered(reply: FailedReply)

    /** The upstream ended the stream with its own last word: the response completed, with [usage] the bill it reported
     *  for the request, or it reported its failure, with [usage] null. */
    public fun finished(usage: Usage?)

    /** The turn is over, whichever way it ended. A request still counted as running is one the turn left behind.
     *  Must not throw: it runs in the finally that settles the turn. */
    public fun turnEnded()
}
