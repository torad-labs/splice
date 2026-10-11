// NEW: the capability-scoped wire grammar providers may drive (plan SPI; L3-as-types).
// A WireSink can DESCRIBE content — open/delta/close blocks, one-shot blocks — but has
// no terminal verbs: emitTerminal/emitError live only on the gateway's SseEmitter, so a
// provider translator cannot fake a clean stop by construction.
package splice.upstream.sse

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex

/** Source observation is independent of callback publication and carries no terminal verbs. */
public interface SourceProgressSink {
    public suspend fun openThinking(): WireBlockIndex

    /** A thinking block splice writes for the client alone (the live exec script). Its writer signs it
     *  with the splice notice signature before closing it, which every request parser drops; a sink
     *  that can cut a block short (a code-mode round between client steps) signs it at the cut. */
    public suspend fun openNotice(): WireBlockIndex = openThinking()

    public suspend fun customToolSource(event: CustomToolSource) {}
}

/** One native frame's typed delivery. The scope ends even when validation drops the frame. */
public fun interface SourceFrameAction {
    public suspend fun deliver(sink: WireSink)
}

/** Backend-authored fields and future events, separate from the content grammar and terminal authority. */
public interface NativeResponseSink {
    /** Wait for the backend-authored opener rather than committing a generated opener early. */
    public fun deferMessageStart() {}

    /** Preserve opaque source fields on this frame's typed writes, never on later synthetic writes. */
    public suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction)

    /** Relay a future nonterminal event. An indexed event requires a real, opened client block. */
    public suspend fun relayEvent(event: JsonObject, index: WireBlockIndex? = null) {}
}

/** Opens the blocks a client sees; each open returns the index every later write names. */
public interface BlockOpening : SourceProgressSink {
    public suspend fun openText(): WireBlockIndex

    public suspend fun openTool(id: String, name: String): WireBlockIndex

    /** DR-119 (neutral passthrough): open a block whose content_block payload is forwarded
     *  VERBATIM as received (server_tool_use / web_search_tool_result today). Deltas ride the
     *  typed verbs or [BlockDeltas.rawDelta]; [BlockEnding.closeBlock] ends it. Returns null when this sink cannot
     *  forward raw blocks — the default, so existing implementors keep their pre-DR-119
     *  behavior (callers treat the block as ignored). */
    public suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex? = null
}

/** The content written into an opened block. */
public interface BlockDeltas {
    public suspend fun textDelta(index: WireBlockIndex, text: String)

    public suspend fun thinkingDelta(index: WireBlockIndex, thinking: String)

    public suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String)

    /** Thinking-block signature delta: providers that receive or synthesize a reasoning
     *  signature forward it here; sinks that don't render signatures ignore it. Default no-op
     *  keeps existing implementors (test Recs, fixtures) source-compatible. */
    public suspend fun signatureDelta(index: WireBlockIndex, signature: String) {}

    /** DR-119: forward one content_block_delta payload VERBATIM (citations_delta today).
     *  Default no-op keeps existing implementors source-compatible. */
    public suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {}
}

/** A block written whole, in one call, with no index for the caller to carry. */
public interface WholeBlocks {
    /** Complete text block in one shot (promote-to-text, mirror). Empty text is a no-op. */
    public suspend fun addTextBlock(text: String)

    /** Encrypted-reasoning replay block (redacted_thinking) — data rides in content_block_start. */
    public suspend fun addRedactedThinking(data: String)
}

/** How blocks end, and how a sink gives up what it holds. */
public interface BlockEnding {
    public suspend fun closeBlock(index: WireBlockIndex)

    public suspend fun closeAll()

    /** Drop output this sink holds but has not yet shown the client: a round whose answer is replaced by another
     *  round's (a websocket round re-served over SSE) must leave nothing of its own behind. Default no-op: a sink
     *  that holds nothing has nothing to drop. */
    public fun discard() {}
}

/** The whole content grammar, for a sink that implements it in one place. A sink that implements it in parts
 *  delegates each part. */
public interface ContentBlockSink : BlockOpening, BlockDeltas, WholeBlocks, BlockEnding

public interface WireSink : ContentBlockSink, NativeResponseSink {
    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        action.deliver(this)
    }
}
