package splice.provider.codex.stream.sink

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonWire
import splice.provider.codex.stream.CodeModeSinkBlocks
import splice.provider.codex.stream.CodeModeSinkCore
import splice.provider.codex.stream.CodeModeSourceObserver
import splice.upstream.sse.BlockDeltas
import splice.upstream.sse.BlockEnding
import splice.upstream.sse.BlockOpening
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WholeBlocks
/** Opening blocks for the client step. */
internal class CodeModeSinkOpens(
    private val core: CodeModeSinkCore,
    private val observer: CodeModeSourceObserver,
) : BlockOpening {
    override suspend fun openThinking(): WireBlockIndex = core.open(CodeModeSinkBlocks.Kind.THINKING)

    override suspend fun openText(): WireBlockIndex = core.open(CodeModeSinkBlocks.Kind.TEXT)

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        core.open(CodeModeSinkBlocks.Kind.TOOL, id, name)

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex =
        core.open(CodeModeSinkBlocks.Kind.RAW, raw = contentBlock)

    override suspend fun customToolSource(event: CustomToolSource) {
        observer.observe(event)
    }
}

/** Content written into opened blocks, whole blocks and endings, each spent against the round's byte budget. */
internal class CodeModeSinkContent(private val core: CodeModeSinkCore) : BlockDeltas, WholeBlocks, BlockEnding {
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        core.write(text.encodeToByteArray().size) { sink ->
            core.blocks.destination(sink, index)?.let {
                sink.textDelta(it, text)
                core.rememberText(text)
            }
        }
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        core.write(thinking.encodeToByteArray().size) { sink ->
            core.blocks.destination(sink, index)?.let { sink.thinkingDelta(it, thinking) }
        }
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        core.write(partialJson.encodeToByteArray().size) { sink ->
            core.blocks.destination(sink, index)?.let { sink.inputJsonDelta(it, partialJson) }
        }
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        core.write(signature.encodeToByteArray().size) { sink ->
            core.blocks.sign(sink, index, signature)
        }
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        core.write(Math.toIntExact(JsonWire.byteSize(delta))) { sink ->
            core.blocks.destination(sink, index)?.let { sink.rawDelta(it, delta) }
        }
    }

    override suspend fun addTextBlock(text: String) {
        core.write(text.encodeToByteArray().size) {
            it.addTextBlock(text)
            core.rememberText(text)
        }
    }

    override suspend fun addRedactedThinking(data: String) {
        core.write(data.encodeToByteArray().size) {
            it.addRedactedThinking(data)
            ReasoningReplay.decodeReasoningEnvelopes(data).forEach(core::rememberNative)
        }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        core.write { core.blocks.close(it, index) }
    }

    override suspend fun closeAll() {
        core.write { core.blocks.closeAll(it) }
    }

    /** A websocket round re-served over SSE answers on this same sink, so its draft is dropped whole and at once:
     *  what the client step holds, the block mappings that name the dropped blocks (a later closeAll would close
     *  them on a buffer that no longer opens them), the writes still waiting for a step, and the continuity the
     *  draft would have reported as delivered. It runs after the failed round's reader has ended (the NeedsSse
     *  branch) and before the SSE round writes, so no write holds the attachment lock against it. */
    override fun discard() {
        core.target?.discard()
        core.blocks.discard()
        core.writes.discard()
        core.resetDelivered()
    }
}
