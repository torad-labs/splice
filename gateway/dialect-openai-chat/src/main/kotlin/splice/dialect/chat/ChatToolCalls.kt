// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: streamed tool-call blocks,
// their pending-open buffering, and CX-01's accumulate-then-parse-at-terminal validation, all moved
// together since they share the buffers. ChatToolArgs folded in as members (accumulateToolArgs,
// firstInvalidToolArgs, invalidArgsReason — names preserved): it accumulated into these buffers and
// had no other reason to exist as its own type.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.spi.BufferCapacity
import splice.spi.WireSink

/** Streamed tool-call state: opened blocks, pending (deferred-open) slots, and CX-01's terminal
 *  validation latch. [frame] resolves and parses the raw delta shape so this class never needs a
 *  JSON-scalar import of its own. */
internal class ChatToolCalls(private val frame: ChatToolFrame, private val prose: ChatProseChannels) {

    internal val toolBlocks = HashMap<Int, WireBlockIndex>()

    // CX-01: full accumulated argument text per opened tool index — the streamed chunks go
    // straight to the wire, so this is the only place the WHOLE buffer exists for a JSON parse
    // at terminal. Capped by BufferCapacity (NF-06).
    internal val toolArgsByIndex = HashMap<Int, StringBuilder>()
    internal var toolArgsInvalid: String? = null
    internal var hasToolUse = false

    // Ids of tool blocks already opened this turn — lets the final-message fold tell an ECHO of a
    // streamed call (suppress) from a call present ONLY in the final consolidated array (emit).
    internal val openedToolIds = HashSet<String>()

    // Deferred opens: backends often emit index+id first and function.name on a later delta.
    // Opening with name="" freezes an empty tool_use on the Anthropic wire — buffer until name
    // arrives (or finish_reason forces a flush).
    internal val pendingTools = HashMap<Int, PendingTool>()
    private var pendingArgsCharCount = 0L
    private var openedArgsCharCount = 0L

    internal data class PendingTool(
        var id: String,
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    // NF-06 buffer-capacity accessors — count every retained map entry, not only synthesized
    // indices/pending opens. A standard explicit-index call bypasses frame.indexCount and leaves
    // pendingTools as soon as its name arrives, while the three opened-call maps keep growing.
    internal val retainedIndexEntryCount: Int get() = minOf(
        Int.MAX_VALUE.toLong(),
        frame.indexCount.toLong() + pendingTools.size + toolBlocks.size +
            toolArgsByIndex.size + openedToolIds.size,
    ).toInt()
    internal val bufferedArgsChars: Int get() = minOf(
        Int.MAX_VALUE.toLong(),
        pendingArgsCharCount + openedArgsCharCount,
    ).toInt()

    // CX-01: the indices with an opened block — what firstInvalidToolArgs walks at terminal.
    internal val openIndices: Set<Int> get() = toolBlocks.keys

    internal suspend fun applyToolCall(tc: JsonObject, sink: WireSink) {
        val parsed = frame.parse(tc)
        val opened = toolBlocks[parsed.index]
        if (opened != null) {
            if (parsed.args.isNotEmpty()) {
                sink.inputJsonDelta(opened, parsed.args)
                accumulateToolArgs(parsed.index, parsed.args)
            }
            return
        }
        val pending = pendingTools.getOrPut(parsed.index) {
            PendingTool(id = parsed.id.ifEmpty { "toolu_${parsed.index}" })
        }
        if (parsed.id.isNotEmpty()) pending.id = parsed.id
        if (parsed.name.isNotEmpty()) pending.name = parsed.name
        if (parsed.args.isNotEmpty()) {
            val before = pending.args.length
            pending.args.append(parsed.args)
            pendingArgsCharCount += (pending.args.length - before).toLong()
        }
        if (pending.name.isNotEmpty()) {
            openPendingTool(parsed.index, pending, sink)
        }
    }

    // Callers guarantee toolBlocks[index] is absent: applyToolCall early-returns on an open block
    // with no suspension before calling here, and flushPendingTools only iterates keys still in
    // pendingTools (removed below in the same uninterruptible span that fills toolBlocks).
    internal suspend fun openPendingTool(index: Int, pending: PendingTool, sink: WireSink) {
        // DR-153: prose closes HERE, at the one place every tool block is born — the streamed path,
        // the finish_reason flush, and the final-message fold all funnel through this method, so a
        // close in the delta path alone would leave the other two overlapping. Anthropic's grammar
        // is one block at a time; a tool_use opened over a live text or thinking block is the same
        // violation DR-143 fixed between the two prose channels.
        prose.closeOpenProse(sink)
        val opened = sink.openTool(pending.id, pending.name.ifEmpty { "tool" })
        toolBlocks[index] = opened
        openedToolIds.add(pending.id)
        hasToolUse = true
        pendingTools.remove(index)
        pendingArgsCharCount -= pending.args.length.toLong()
        if (pending.args.isNotEmpty()) {
            sink.inputJsonDelta(opened, pending.args.toString())
            accumulateToolArgs(index, pending.args.toString())
        }
    }

    internal suspend fun flushPendingTools(sink: WireSink) {
        if (pendingTools.isEmpty()) return
        // Snapshot keys — openPendingTool mutates pendingTools.
        pendingTools.keys.toList().forEach { index ->
            pendingTools[index]?.let { openPendingTool(index, it, sink) }
        }
    }

    // CX-01: bounded accumulation of an opened tool's full argument text (streamed chunks go to the
    // wire; this is the only place the whole buffer exists to parse at terminal). NF-06 cap.
    private fun accumulateToolArgs(index: Int, chunk: String) {
        val buf = toolArgsByIndex.getOrPut(index) { StringBuilder() }
        if (buf.length >= BufferCapacity.MAX_BUFFERED_CHARS) return
        val before = buf.length
        buf.append(chunk)
        openedArgsCharCount += (buf.length - before).toLong()
    }

    /** CX-01: the first opened tool whose accumulated args are empty or not valid JSON, or null when
     *  all parse. An opened tool with zero argument text is malformed ({} for a tool that needed
     *  args). */
    internal fun firstInvalidToolArgs(): String? =
        openIndices.firstNotNullOfOrNull { index -> invalidArgsReason(toolArgsByIndex[index]?.toString().orEmpty()) }

    /** null when [text] is valid non-empty tool-argument JSON, else the reason. */
    private fun invalidArgsReason(text: String): String? {
        if (text.isBlank()) return "empty arguments for an opened tool call"
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(text).run { null }
        } catch (ignored: kotlinx.serialization.SerializationException) {
            "malformed JSON"
        } catch (ignored: IllegalArgumentException) {
            "malformed JSON"
        }
    }
}
