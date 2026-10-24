package splice.provider.codex.diagnostics

import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

/** History-only fixtures must never synthesize or claim interactive client delivery. */
internal object CodeModeGatewayProbeSink : WireSink {
    override suspend fun openText(): WireBlockIndex = delivery()
    override suspend fun openThinking(): WireBlockIndex = delivery()
    override suspend fun openTool(id: String, name: String): WireBlockIndex = callback()
    override suspend fun textDelta(index: WireBlockIndex, text: String): Unit = delivery()
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String): Unit = delivery()
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String): Unit = callback()
    override suspend fun closeBlock(index: WireBlockIndex): Unit = delivery()
    override suspend fun closeAll(): Unit = delivery()
    override suspend fun addTextBlock(text: String): Unit = delivery()
    override suspend fun addRedactedThinking(data: String): Unit = delivery()

    private fun delivery(): Nothing = error("unexpected client delivery in history-only probe")
    private fun callback(): Nothing = error("unexpected callback publication in history-only probe")
}
