// NEW: kt-no-exception-as-outcome (2026-10-09) — how one SSE attempt's read ENDED EARLY, as a value.
//
// A tear before the client saw a frame, and a frame over our size limit, used to be thrown out of the event flow the
// translator collects, so every translator carried a catch list that knew to rethrow them. The flow ends instead:
// [TearAwareEvents] records the fact here and completes, the translator sees an ordinary end of stream, and
// [SseRoundConsume] reads the fact after the translator returns. Everything the translator was handed before the end
// has been delivered by then, so no frame is held back or written late. What a translator writes on a flow that ended
// with no terminal is its closing: it flushes the tool calls it was holding and closes its blocks. [gate] keeps every
// such write off a client whose turn ends on the recorded ending, so a tear before any client-visible content leaves
// nothing shown and the attempt stays recoverable (a flushed half-built tool call is content, and blocks the reissue).
// The websocket path takes the same shape for its re-serve (SseReserve).
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.StreamRead
import splice.upstream.sse.WireSink
import splice.upstream.transport.SseFrameTooLarge
import java.io.IOException

/** Per attempt: SseRoundConsume makes one for each stream it reads, because a re-issued request is a new attempt.
 *  It holds when the attempt's request was posted and, once the stream stops early, how it ended. */
internal class RoundEnd(val postedAtMs: Long? = null) {
    @Volatile
    var torn: IOException? = null

    @Volatile
    var oversized: SseFrameTooLarge? = null

    private val ended: Boolean get() = torn != null || oversized != null

    /** The sink the translator writes to: closing is the one write it makes on a flow that ended early, and it must
     *  not reach a client whose turn ends on the recorded ending instead. */
    fun gate(inner: WireSink): WireSink = GatedSink(inner, this)

    /** How the attempt's stream ended early, or null when it ran to its end and the translator's outcome stands. */
    fun early(): StreamRead<Nothing>? =
        torn?.let { StreamRead.Torn(it) } ?: oversized?.let { StreamRead.Oversized(it) }

    /** Writes once the attempt has ended early go nowhere: an opener answers with an index no later write can name. */
    private class GatedSink(private val inner: WireSink, private val end: RoundEnd) : WireSink by inner {
        private val notOpened = WireBlockIndex(-1)

        override suspend fun openText(): WireBlockIndex = if (end.ended) notOpened else inner.openText()

        override suspend fun openThinking(): WireBlockIndex = if (end.ended) notOpened else inner.openThinking()

        override suspend fun openTool(id: String, name: String): WireBlockIndex =
            if (end.ended) notOpened else inner.openTool(id, name)

        override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex? =
            if (end.ended) notOpened else inner.openRawBlock(contentBlock)

        override suspend fun textDelta(index: WireBlockIndex, text: String) {
            if (!end.ended) inner.textDelta(index, text)
        }

        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
            if (!end.ended) inner.thinkingDelta(index, thinking)
        }

        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
            if (!end.ended) inner.signatureDelta(index, signature)
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
            if (!end.ended) inner.inputJsonDelta(index, partialJson)
        }

        override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
            if (!end.ended) inner.rawDelta(index, delta)
        }

        override suspend fun addTextBlock(text: String) {
            if (!end.ended) inner.addTextBlock(text)
        }

        override suspend fun addRedactedThinking(data: String) {
            if (!end.ended) inner.addRedactedThinking(data)
        }

        override suspend fun closeBlock(index: WireBlockIndex) {
            if (!end.ended) inner.closeBlock(index)
        }

        override suspend fun closeAll() {
            if (!end.ended) inner.closeAll()
        }
    }
}
