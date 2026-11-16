// NEW: server mode (docs/specs/server-mode.md, F5). What the server listener read of an answer it relayed for an
// OpenAI-style client straight from the provider's server, with no head between them: the dialect reads each frame
// on its way to the client, and the ledger row of that request is written from what it read. Counts and rates only;
// the text of the answer is passed on and never kept.
package splice.core.server

/** One relayed answer as it has been read so far. */
public interface AnswerReading {
    /** The tokens the server billed, or null while it reported none. */
    public val tokens: ServedTokens?

    /** How fast the server read and wrote, from its `timings`; null when it sent none. */
    public val speed: TurnSpeed?

    /** The model the server said answered, or null while it named none. */
    public val model: String?

    /** Whether the answer reached its own end: a stream the server closed cleanly, or a body read whole. An answer
     *  the server broke off partway never does. */
    public val finished: Boolean

    /** Whether the server ended its stream with its own end marker (`[DONE]`), rather than just closing it. */
    public val ended: Boolean

    /** When the answer first carried anything of the model's (a word, a thought or a tool call), in epoch
     *  milliseconds; null while it has carried nothing. */
    public val spokeAtMs: Long?

    /** The longest the answer waited for more of the model's output after it first carried some, in milliseconds (F6);
     *  null while it has carried nothing. */
    public val stallMs: Long?
}
