// NEW: the capability-scoped wire grammar providers may drive (plan SPI; L3-as-types).
// A WireSink can DESCRIBE content — open/delta/close blocks, one-shot blocks — but has
// no terminal verbs: emitTerminal/emitError live only on the gateway's SseEmitter, so a
// provider translator cannot fake a clean stop by construction.
package splice.spi

import splice.core.index.WireBlockIndex

public interface WireSink {
    public suspend fun openText(): WireBlockIndex

    public suspend fun openThinking(): WireBlockIndex

    public suspend fun openTool(id: String, name: String): WireBlockIndex

    public suspend fun textDelta(index: WireBlockIndex, text: String)

    public suspend fun thinkingDelta(index: WireBlockIndex, thinking: String)

    public suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String)

    public suspend fun closeBlock(index: WireBlockIndex)

    public suspend fun closeAll()

    /** Complete text block in one shot (promote-to-text, mirror). Empty text is a no-op. */
    public suspend fun addTextBlock(text: String)

    /** Encrypted-reasoning replay block (redacted_thinking) — data rides in content_block_start. */
    public suspend fun addRedactedThinking(data: String)
}
