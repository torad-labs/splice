// NEW: upstream block identities survive client changes; real wire indices belong to one attachment.
package splice.provider.codex.stream

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.SpliceNotice
import splice.upstream.sse.WireSink

internal class CodeModeSinkBlocks {
    /** NOTICE is a thinking block splice writes for the client alone (the live exec script, V4-456). */
    enum class Kind { TEXT, THINKING, TOOL, RAW, NOTICE }
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
        Kind.THINKING, Kind.NOTICE -> sink.openThinking()
        Kind.TOOL -> sink.openTool(block.id, block.name)
        Kind.RAW -> sink.openRawBlock(checkNotNull(block.raw))
    }

    suspend fun destination(sink: WireSink, index: WireBlockIndex): WireBlockIndex? {
        val block = blocks[index.value] ?: return null
        return block.index ?: openOn(sink, block)?.also { block.index = it }
    }

    /** Where a signature goes. A notice cut by [detach] was signed there, so its writer's own closing
     *  signature never reopens it as an empty block in the next client step. */
    suspend fun signatureDestination(sink: WireSink, index: WireBlockIndex): WireBlockIndex? =
        if (isNotice(index)) blocks[index.value]?.index else destination(sink, index)

    fun isNotice(index: WireBlockIndex): Boolean = blocks[index.value]?.kind == Kind.NOTICE

    /** Closes every block open on [sink]. A notice is signed first, as its writer would have signed it:
     *  cut short unsigned, Claude Code would keep it and replay the script as the model's reasoning. */
    suspend fun detach(sink: WireSink) {
        for (block in blocks.values) {
            block.index?.let { index ->
                if (block.kind == Kind.NOTICE) sink.signatureDelta(index, SpliceNotice.SIGNATURE)
                sink.closeBlock(index)
            }
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
