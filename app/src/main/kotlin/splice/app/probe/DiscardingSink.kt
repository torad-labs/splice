// NEW: Oct 12, 2026 — the sink a Compare send's stream is translated into: nothing is shown to a client, so every
// frame is dropped and only the translator's outcome (its usage) is kept.
package splice.app.probe

import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

internal class DiscardingSink : WireSink {
    override suspend fun openText() = WireBlockIndex(0)

    override suspend fun openThinking() = WireBlockIndex(0)

    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)

    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit

    override suspend fun closeBlock(index: WireBlockIndex) = Unit

    override suspend fun closeAll() = Unit

    override suspend fun addTextBlock(text: String) = Unit

    override suspend fun addRedactedThinking(data: String) = Unit
}
