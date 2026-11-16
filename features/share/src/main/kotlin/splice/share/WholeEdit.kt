// NEW: server mode (docs/specs/server-mode.md, F4: "the OpenAI route answers a lookup of one model"). A relayed answer
// that is no stream, read on its way to the client. A relay passes such an answer on as the server gave it. One that
// answers for a provider's own server may be asked for less than that server answers, such as one model of its list,
// so what the client gets is read out of the server's whole answer. What that answer means is the reader's to know,
// which keeps a server's wire out of this module, as StreamEdit does for a stream.
package splice.share

/** What a relay's client gets for an answer that was read whole. */
public sealed class WholeAnswer {
    /** [body], under the server's own status and type. */
    public class Given(public val body: ByteArray) : WholeAnswer()

    /** A refusal in the relay's own voice: [status], and [why] as its message. */
    public class Refused(public val status: Int, public val why: String) : WholeAnswer()
}

/** What reads one answer that is no stream. */
public fun interface WholeEdit {
    /** What the client gets for [body], an answer the server gave whole and did not refuse. An answer the server
     *  refused is never read: it goes back as the server gave it. */
    public fun read(body: ByteArray): WholeAnswer
}
