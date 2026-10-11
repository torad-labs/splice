// NEW: the reasoning-continuation fold buffer (the codex 518n-2 fix, gateway side). A round's
// REASONING frames must stream to the client LIVE (thinking stays visible across folds), but its
// tentative FINAL output (message text + function_call/tool_use) must NOT reach the client until the
// terminal event proves the round was not truncated. This wrapper passes reasoning ops straight
// through to the real sink and BUFFERS the final-output ops as deferred operations against
// placeholder indices; [flush] replays them (allocating real block indices THEN, so they stay
// monotonic after the live reasoning), [discard] drops them. It has no terminal verbs — L3 stays a
// property of SseEmitter; a truncated round can never emit a clean stop through here.
package splice.head.wire

import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

internal class BufferingWireSink(private val real: WireSink) : WireSink {
    private val held = HeldOutput()

    // ── buffered final output ───────────────────────────────────────────────
    override suspend fun openText(): WireBlockIndex = held.openText()

    override suspend fun openTool(id: String, name: String): WireBlockIndex = held.openTool(id, name)

    override suspend fun addTextBlock(text: String) {
        held.addText(text)
    }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        if (index.value < 0) held.textDelta(index, text) else real.textDelta(index, text)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        if (index.value < 0) held.inputJsonDelta(index, partialJson) else real.inputJsonDelta(index, partialJson)
    }

    // ── live reasoning passthrough ──────────────────────────────────────────
    override suspend fun openThinking(): WireBlockIndex = real.openThinking()

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        real.thinkingDelta(index, thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        real.signatureDelta(index, signature)
    }

    override suspend fun addRedactedThinking(data: String) {
        real.addRedactedThinking(data)
    }

    // ── close routing ───────────────────────────────────────────────────────
    override suspend fun closeBlock(index: WireBlockIndex) {
        if (index.value < 0) held.closeBlock(index) else real.closeBlock(index)
    }

    override suspend fun closeAll() {
        real.closeAll() // close any live reasoning blocks now
        held.closeAllOpen() // buffered blocks close at flush
    }

    /** Replay the buffered final output as real wire frames (the round finished cleanly). Real block
     *  indices are allocated HERE, after all live reasoning indices, so the wire stays monotonic. */
    public suspend fun flush() {
        held.replayInto(real)
    }

    /** Drop the tentative output (the round was truncated — its answer was built on cut reasoning). */
    override fun discard() {
        held.drop()
    }
}

/** The final output a round has produced but not yet shown: recorded as deferred operations against placeholder
 *  indices, replayed against the real sink at [replayInto], dropped at [drop]. Placeholder refs are NEGATIVE (real
 *  indices are >= 0), so routing (buffered vs live) is a sign check and placeholders never collide with real ones. */
private class HeldOutput {
    private sealed class Op
    private data class OpenText(val ref: Int) : Op()
    private data class OpenTool(val ref: Int, val id: String, val name: String) : Op()
    private data class TextDelta(val ref: Int, val text: String) : Op()
    private data class InputJsonDelta(val ref: Int, val partialJson: String) : Op()
    private data class CloseBlock(val ref: Int) : Op()
    private data class AddText(val text: String) : Op()

    private val ops = mutableListOf<Op>()
    private val openRefs = LinkedHashSet<Int>()
    private var nextRef = -1

    private fun mintRef(): Int = nextRef--.also { openRefs.add(it) }

    fun openText(): WireBlockIndex = WireBlockIndex(mintRef()).also { ops.add(OpenText(it.value)) }

    fun openTool(id: String, name: String): WireBlockIndex =
        WireBlockIndex(mintRef()).also { ops.add(OpenTool(it.value, id, name)) }

    fun addText(text: String) {
        if (text.isNotEmpty()) ops.add(AddText(text))
    }

    fun textDelta(index: WireBlockIndex, text: String) {
        ops.add(TextDelta(index.value, text))
    }

    fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        ops.add(InputJsonDelta(index.value, partialJson))
    }

    fun closeBlock(index: WireBlockIndex) {
        openRefs.remove(index.value)
        ops.add(CloseBlock(index.value))
    }

    fun closeAllOpen() {
        for (ref in openRefs) ops.add(CloseBlock(ref))
        openRefs.clear()
    }

    suspend fun replayInto(real: WireSink) {
        val realIndex = HashMap<Int, WireBlockIndex>()
        for (op in ops) replay(op, real, realIndex)
        drop()
    }

    // getValue (not ?.let): an open is always replayed before its deltas/close (they buffered in
    // order after it), so the ref is always mapped — a miss is a real bug worth failing loudly on.
    private suspend fun replay(op: Op, real: WireSink, realIndex: HashMap<Int, WireBlockIndex>) {
        when (op) {
            is OpenText -> realIndex[op.ref] = real.openText()
            is OpenTool -> realIndex[op.ref] = real.openTool(op.id, op.name)
            is TextDelta -> real.textDelta(realIndex.getValue(op.ref), op.text)
            is InputJsonDelta -> real.inputJsonDelta(realIndex.getValue(op.ref), op.partialJson)
            is CloseBlock -> real.closeBlock(realIndex.getValue(op.ref))
            is AddText -> real.addTextBlock(op.text)
        }
    }

    fun drop() {
        ops.clear()
        openRefs.clear()
    }
}
