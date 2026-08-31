// NEW: the capability-scoped wire grammar providers may drive (plan SPI; L3-as-types).
// A WireSink can DESCRIBE content — open/delta/close blocks, one-shot blocks — but has
// no terminal verbs: emitTerminal/emitError live only on the gateway's SseEmitter, so a
// provider translator cannot fake a clean stop by construction.
package splice.spi

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex

public interface WireSink {
    public suspend fun openText(): WireBlockIndex

    public suspend fun openThinking(): WireBlockIndex

    public suspend fun openTool(id: String, name: String): WireBlockIndex

    public suspend fun textDelta(index: WireBlockIndex, text: String)

    public suspend fun thinkingDelta(index: WireBlockIndex, thinking: String)

    public suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String)

    /** Thinking-block signature delta: providers that receive or synthesize a reasoning
     *  signature forward it here; sinks that don't render signatures ignore it. Default no-op
     *  keeps existing implementors (test Recs, fixtures) source-compatible. */
    public suspend fun signatureDelta(index: WireBlockIndex, signature: String) {}

    public suspend fun closeBlock(index: WireBlockIndex)

    public suspend fun closeAll()

    /** Complete text block in one shot (promote-to-text, mirror). Empty text is a no-op. */
    public suspend fun addTextBlock(text: String)

    /** Encrypted-reasoning replay block (redacted_thinking) — data rides in content_block_start. */
    public suspend fun addRedactedThinking(data: String)

    /** DR-119 (neutral passthrough): open a block whose content_block payload is forwarded
     *  VERBATIM as received (server_tool_use / web_search_tool_result today). Deltas ride the
     *  typed verbs or [rawDelta]; [closeBlock] ends it. Returns null when this sink cannot
     *  forward raw blocks — the default, so existing implementors keep their pre-DR-119
     *  behavior (callers treat the block as ignored). */
    public suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex? = null

    /** DR-119: forward one content_block_delta payload VERBATIM (citations_delta today).
     *  Default no-op keeps existing implementors source-compatible. */
    public suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {}
}
