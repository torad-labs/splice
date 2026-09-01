// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the buffers now live
// with the code that writes them instead of on the god class (state moved WITH the behaviour that
// owns it). Each write keeps its exact statement ORDER — buffer append, then the flag, then the
// sink call — so the wire sequence the translator goldens pin is byte-identical.
package splice.dialect.passthrough

import splice.core.index.WireBlockIndex
import splice.spi.WireSink

/** The passthrough dialect's prose channels: the text and thinking buffers, the two content flags
 *  the empty-turn honesty gate reads, and the two delta writes that fill them. */
internal class PassthroughProseChannels {

    internal val textBuf = StringBuilder()
    internal val thinkingBuf = StringBuilder()
    internal var emittedText = false

    // CX-09: the flag means "the client RECEIVED reasoning", not "a block was opened" — see the
    // note on [thinkingDelta].
    internal var emittedThinking = false

    internal suspend fun textDelta(wire: WireBlockIndex, t: String, sink: WireSink) {
        textBuf.append(t)
        // DR-75: the flag means "the client RECEIVED text" — the CX-09 law's text flavor. An
        // empty delta latching it graded a zero-character turn as content-bearing, short-
        // circuiting the empty-turn honesty gate. Append + sink stay unconditional: the kimi
        // goldens pin the forwarded wire bytes.
        if (t.isNotEmpty()) emittedText = true
        sink.textDelta(wire, t)
    }

    internal suspend fun thinkingDelta(wire: WireBlockIndex, t: String, sink: WireSink) {
        thinkingBuf.append(t)
        // CX-09: the flag means "the client RECEIVED reasoning", not "a block was opened".
        // Kimi can open a thinking block and close it having sent nothing; counting that
        // as content short-circuits the empty-turn honesty gate and lets a turn carrying
        // literally zero characters end as a clean terminal — the L3 violation CX-09
        // exists to close.
        //
        // DR-144: this used to claim it sets the flag "where chat and responses set theirs", which
        // is FALSE and misled a reader about two other dialects. Chat latches on isNotEmpty (see
        // ChatProseFold.reasoningDeltaText) and responses latches on reaching the sink at all, so
        // isNotBlank here is STRICTER than both siblings, not the same rule. The divergence is
        // deliberate and adjudicated: passthrough alone is defending against the observed Kimi
        // shape, and the two OpenAI-family dialects forward what the vendor sent. Do not
        // "harmonize" them — the chat side is pinned by a DR-144 arm that will red.
        if (t.isNotBlank()) emittedThinking = true
        sink.thinkingDelta(wire, t)
    }
}
