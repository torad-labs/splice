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
    ) {
        /** A notice its writer signed where it is open now; a cut closes it without a second signature. */
        var signed = false
    }

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

    /** Signs [index]. A notice is signed only where it is open in this step: one a cut already signed and
     *  closed, or one no kept delta opened, is never reopened as an empty block just to carry a signature. */
    suspend fun sign(sink: WireSink, index: WireBlockIndex, signature: String) {
        val block = blocks[index.value] ?: return
        if (block.kind != Kind.NOTICE) {
            destination(sink, index)?.let { sink.signatureDelta(it, signature) }
            return
        }
        val open = block.index ?: return
        sink.signatureDelta(open, signature)
        block.signed = true
    }

    fun isNotice(index: WireBlockIndex): Boolean = blocks[index.value]?.kind == Kind.NOTICE

    /** Closes every block open on [sink]. A notice its writer has not signed is signed first, as its writer
     *  would have signed it: cut short unsigned, Claude Code would keep it and replay the script as the
     *  model's reasoning. */
    suspend fun detach(sink: WireSink) {
        for (block in blocks.values) {
            block.index?.let { index ->
                if (block.kind == Kind.NOTICE && !block.signed) sink.signatureDelta(index, SpliceNotice.SIGNATURE)
                sink.closeBlock(index)
            }
            block.index = null
            block.signed = false
        }
    }

    suspend fun close(sink: WireSink, index: WireBlockIndex) {
        blocks.remove(index.value)?.index?.let { sink.closeBlock(it) }
    }

    /** Forgets every block without a frame: the client step dropped what they opened, so no later close or
     *  delta may name them. */
    fun discard() {
        blocks.clear()
    }

    suspend fun closeAll(sink: WireSink) {
        detach(sink)
        blocks.clear()
    }
}
