package splice.provider.codex.diagnostics

import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

internal class CodeModeGatewayBridgeSink : WireSink {
    val callbackIds = mutableListOf<String>()
    val text = StringBuilder()
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(0)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        callbackIds += id
        return WireBlockIndex(callbackIds.lastIndex)
    }
    override suspend fun textDelta(index: WireBlockIndex, text: String) { this.text.append(text) }
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) { this.text.append(text) }
    override suspend fun addRedactedThinking(data: String) = Unit
}
