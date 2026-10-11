// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: streamed tool-call blocks,
// their pending-open buffering, and CX-01's accumulate-then-parse-at-terminal validation, all moved
// together since they share the buffers. ChatToolArgs folded in as members (accumulateToolArgs,
// firstInvalidToolArgs, invalidArgsReason — names preserved): it accumulated into these buffers and
// had no other reason to exist as its own type.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity

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

    // Tool blocks already opened this turn, by id — lets the final-message fold tell an ECHO of a
    // streamed call (complete its arguments, never open it twice) from a call present ONLY in the
    // final consolidated array (emit). The value is the call's tool index.
    internal val openedIndexById = HashMap<String, Int>()

    // Opened calls whose id was minted here ("toolu_<n>") because the stream never carried one, and the
    // name each opened call went out under: what lets the fold match a final echo that arrives WITH an id
    // back to the streamed call by its position and name.
    private val synthesizedIndices = HashSet<Int>()
    private val openedNames = HashMap<Int, String>()

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
        var synthesizedId: Boolean = false,
    )

    // NF-06 buffer-capacity accessors — count every retained map entry, not only synthesized
    // indices/pending opens. A standard explicit-index call bypasses frame.indexCount and leaves
    // pendingTools as soon as its name arrives, while the three opened-call maps keep growing.
    internal val retainedIndexEntryCount: Int get() = minOf(
        Int.MAX_VALUE.toLong(),
        frame.indexCount.toLong() + pendingTools.size + toolBlocks.size +
            toolArgsByIndex.size + openedIndexById.size + synthesizedIndices.size + openedNames.size,
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
            PendingTool(id = parsed.id.ifEmpty { "toolu_${parsed.index}" }, synthesizedId = parsed.id.isEmpty())
        }
        if (parsed.id.isNotEmpty()) {
            pending.id = parsed.id
            pending.synthesizedId = false
        }
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
        openedIndexById[pending.id] = index
        openedNames[index] = pending.name.ifEmpty { "tool" }
        if (pending.synthesizedId) synthesizedIndices.add(index)
        hasToolUse = true
        pendingTools.remove(index)
        pendingArgsCharCount -= pending.args.length.toLong()
        if (pending.args.isNotEmpty()) {
            sink.inputJsonDelta(opened, pending.args.toString())
            accumulateToolArgs(index, pending.args.toString())
        }
    }

    /** Every streamed call's tool index, opened or still pending, in the order the vendor lists them. */
    internal fun streamedIndices(): List<Int> = (toolBlocks.keys + pendingTools.keys).sorted()

    /** The streamed call a final-message call with id [id] and name [name] echoes, found by id, else — for a call that
     *  was streamed without an id and so carries a minted one — by its [position] in the final array and its name. */
    internal fun echoedIndex(id: String, name: String, position: Int, streamed: List<Int>): Int? {
        val byId = if (id.isEmpty()) {
            null
        } else {
            openedIndexById[id] ?: pendingTools.entries.firstOrNull { it.value.id == id }?.key
        }
        return byId ?: streamed.getOrNull(position)?.takeIf { isMintedEchoOf(it, name) }
    }

    private fun isMintedEchoOf(candidate: Int, name: String): Boolean {
        val minted = candidate in synthesizedIndices || pendingTools[candidate]?.synthesizedId == true
        val streamedName = openedNames[candidate] ?: pendingTools[candidate]?.name.orEmpty()
        return minted && (streamedName.isEmpty() || streamedName == name)
    }

    /** The final message's complete arguments for an already-opened call: when the stream delivered only a prefix of
     *  them, the rest is sent now, so the client receives the whole input instead of truncated JSON. Arguments that
     *  are not an extension of what was streamed are left alone: they cannot be corrected on the wire, and the
     *  terminal validation reports them. */
    internal suspend fun completeOpenedArgs(index: Int, finalArgs: String, sink: WireSink) {
        val block = toolBlocks[index] ?: return
        val streamed = toolArgsByIndex[index]?.toString().orEmpty()
        if (finalArgs.length > streamed.length && finalArgs.startsWith(streamed)) {
            val rest = finalArgs.substring(streamed.length)
            sink.inputJsonDelta(block, rest)
            accumulateToolArgs(index, rest)
        }
    }

    /** The same completion for a call still pending, whose arguments have not gone to the wire yet. */
    internal fun completePendingArgs(pending: PendingTool, finalArgs: String) {
        if (finalArgs.length > pending.args.length && finalArgs.startsWith(pending.args)) {
            val before = pending.args.length
            pending.args.append(finalArgs, before, finalArgs.length)
            pendingArgsCharCount += (pending.args.length - before).toLong()
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
