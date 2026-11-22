// NEW: modes hooks Phase 2 (modes/README.md, "Hooks"): buffers one round's WireSink writes while `response` or
// `on_stop` hooks inspect the completed message, so a hook that refuses, replaces, rewrites or continues leaves
// nothing premature on the client wire.
package splice.harness.hook

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.Usage
import splice.harness.MessageAssembly
import splice.upstream.sse.BlockDeltas
import splice.upstream.sse.BlockEnding
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WholeBlocks
import splice.upstream.sse.WireSink

/** One buffered write, replayed against a target sink with its remapped block indices. */
internal fun interface ReplayStep {
    suspend fun replay(target: WireSink, map: MutableMap<Int, WireBlockIndex>)
}

internal class HookBufferLog {
    private val steps = mutableListOf<ReplayStep>()
    private var nextIndex = 0

    fun alloc(): WireBlockIndex = WireBlockIndex(nextIndex++)

    fun add(step: ReplayStep) {
        steps += step
    }

    fun clear() {
        steps.clear()
        nextIndex = 0
    }

    suspend fun replayInto(target: WireSink) {
        val map = mutableMapOf<Int, WireBlockIndex>()
        for (step in steps) {
            step.replay(target, map)
        }
    }
}

private class BufferedDeltas(private val log: HookBufferLog) : BlockDeltas {
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        log.add { target, map -> map[index.value]?.let { target.textDelta(it, text) } }
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        log.add { target, map -> map[index.value]?.let { target.thinkingDelta(it, thinking) } }
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        log.add { target, map -> map[index.value]?.let { target.inputJsonDelta(it, partialJson) } }
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        log.add { target, map -> map[index.value]?.let { target.signatureDelta(it, signature) } }
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        log.add { target, map -> map[index.value]?.let { target.rawDelta(it, delta) } }
    }
}

private class BufferedWhole(private val log: HookBufferLog) : WholeBlocks, BlockEnding {
    override suspend fun addTextBlock(text: String) {
        log.add { target, _ -> target.addTextBlock(text) }
    }

    override suspend fun addRedactedThinking(data: String) {
        log.add { target, _ -> target.addRedactedThinking(data) }
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        log.add { target, map -> map[index.value]?.let { target.closeBlock(it) } }
    }

    override suspend fun closeAll() {
        log.add { target, _ -> target.closeAll() }
    }

    override fun discard() {
        log.clear()
    }
}

internal class HookBufferSink(
    val message: MessageAssembly = MessageAssembly(emptySet()),
    private val log: HookBufferLog = HookBufferLog(),
    deltas: BlockDeltas = BufferedDeltas(log),
    whole: BufferedWhole = BufferedWhole(log),
) : WireSink, BlockDeltas by deltas, WholeBlocks by whole, BlockEnding by whole {

    override fun deferMessageStart() {
        log.add { target, _ -> target.deferMessageStart() }
    }

    override fun openingUsage(usage: Usage) {
        log.add { target, _ -> target.openingUsage(usage) }
    }

    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        message.accept(event)
        action.deliver(this)
    }

    override suspend fun relayEvent(event: JsonObject, index: WireBlockIndex?) {
        log.add { target, map -> target.relayEvent(event, index?.let { map[it.value] }) }
    }

    override suspend fun openThinking(): WireBlockIndex = log.alloc().also { idx ->
        log.add { target, map -> map[idx.value] = target.openThinking() }
    }

    override suspend fun customToolSource(event: CustomToolSource) {
        log.add { target, _ -> target.customToolSource(event) }
    }

    override suspend fun openText(): WireBlockIndex = log.alloc().also { idx ->
        log.add { target, map -> map[idx.value] = target.openText() }
    }

    override suspend fun openTool(id: String, name: String): WireBlockIndex = log.alloc().also { idx ->
        log.add { target, map -> map[idx.value] = target.openTool(id, name) }
    }

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = log.alloc().also { idx ->
        log.add { target, map -> target.openRawBlock(contentBlock)?.let { map[idx.value] = it } }
    }

    suspend fun replayInto(target: WireSink): Unit = log.replayInto(target)
}
