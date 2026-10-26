// NEW: where a vendor routes one turn's requests, asked per attempt (Oct 26, 2026). Muse pins a conversation to one
// place in its gateway with a header, and the conversation's prompt cache lives there. A request sent to that place
// while a generation is still running there waits behind it: on Oct 26 a re-send after Meta's gateway gave up on a
// generation, and the four requests after it, waited 18 minutes. The transport and the head only report what each
// request met, and the vendor decides when its conversation leaves that place, the way VendorRefusals keeps a vendor's
// refusals in its own module.
package splice.upstream

/** One turn's route upstream, from [Provider.route]. The transport and the head say what happened to each request it
 *  routed; which of those means the conversation must leave its route is the vendor's to know. */
public interface VendorRoute {
    /** The headers that route the request about to be sent. From here that request may be running upstream until
     *  [answered] or [finished] hears how it ended, and one sent after it is routed knowing whether it did. */
    public fun sending(): Map<String, String>

    /** The upstream answered the request with [status] and [body] instead of a stream. Whether that answer ended what
     *  the request started upstream is the vendor's to know. */
    public fun answered(status: Int, body: String)

    /** The upstream ended the stream with its own last word: the response completed, or it reported its failure. */
    public fun finished()

    /** The turn is over, whichever way it ended. A request still counted as running is one the turn left behind.
     *  Must not throw: it runs in the finally that settles the turn. */
    public fun turnEnded()
}
