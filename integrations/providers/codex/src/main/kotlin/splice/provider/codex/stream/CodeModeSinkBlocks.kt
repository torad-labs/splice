// NEW: upstream block identities survive client changes; real wire indices belong to one attachment.
package splice.provider.codex.stream

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

internal class CodeModeSinkBlocks {
    enum class Kind { TEXT, THINKING, TOOL, RAW }
    private data class Block(
        val kind: Kind,
        val id: String,
        val name: String,
        val raw: JsonObject?,
        var index: WireBlockIndex? = null,
    )

    private val blocks = linkedMapOf<Int, Block>()
    private var sequence = 0

    suspend fun open(
        sink: WireSink?,
        kind: Kind,
        id: String = "",
        name: String = "",
        raw: JsonObject? = null,
    ): WireBlockIndex {
        val token = sequence++
        val block = Block(kind, id, name, raw)
        blocks[token] = block
        if (sink != null) block.index = openOn(sink, block)
        return WireBlockIndex(token)
    }

    private suspend fun openOn(sink: WireSink, block: Block): WireBlockIndex? = when (block.kind) {
        Kind.TEXT -> sink.openText()
        Kind.THINKING -> sink.openThinking()
        Kind.TOOL -> sink.openTool(block.id, block.name)
        Kind.RAW -> sink.openRawBlock(checkNotNull(block.raw))
    }

    suspend fun destination(sink: WireSink, index: WireBlockIndex): WireBlockIndex? {
        val block = blocks[index.value] ?: return null
        return block.index ?: openOn(sink, block)?.also { block.index = it }
    }

    suspend fun detach(sink: WireSink) {
        for (block in blocks.values) {
            block.index?.let { sink.closeBlock(it) }
            block.index = null
        }
    }

    suspend fun close(sink: WireSink, index: WireBlockIndex) {
        blocks.remove(index.value)?.index?.let { sink.closeBlock(it) }
    }

    suspend fun closeAll(sink: WireSink) {
        detach(sink)
        blocks.clear()
    }
}
