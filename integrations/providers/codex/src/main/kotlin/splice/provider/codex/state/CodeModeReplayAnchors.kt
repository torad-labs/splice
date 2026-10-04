// NEW: v5 placements use local history anchors and minted ids instead of canonical prefix digests.
package splice.provider.codex.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.provider.codex.CodeModeInputBoundary
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

@Serializable
internal data class CodeModeHistoryAnchor(val itemDigest: String?, val occurrence: Int, val logicalTail: Int = 0)

@Serializable
internal data class CodeModeReplayAnchors(
    val baseline: CodeModeHistoryAnchor,
    val native: Map<Int, CodeModeHistoryAnchor>,
)

internal object CodeModeAnchorCapture {
    fun inputBoundary(
        bodyJson: String,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeInputBoundary? {
        val input = codec.root(bodyJson)?.second ?: return null
        val conversation = codec.conversation(codec.projection.project(input))
        val body = conversation.body
        val natives = body.nativeSegments.map { CodeModeNativeSegment(it.logicalOffset, it.items) }
        return CodeModeInputBoundary(
            input.size - conversation.preamble.size,
            body.logicalItems.size,
            "",
            "",
            natives,
        ).also { it.replayAnchors = capture(body.logicalItems, natives, completed, codec) }
    }

    fun capture(
        items: List<JsonElement>,
        natives: List<CodeModeNativeSegment>,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeReplayAnchors {
        val owned = completed.flatMap { it.clientIds() + it.outerCallId }.toSet()
        val continuity = completed.flatMap(CodeModeRecord::continuity).toSet()
        val fingerprints = items.map { InputDigest.hex(it) }
        val eligible = items.indices.filter { codec.callId(items[it]) !in owned && items[it] !in continuity }
        fun at(boundary: Int): CodeModeHistoryAnchor {
            val prior = eligible.lastOrNull { it < boundary } ?: return CodeModeHistoryAnchor(null, 0, boundary)
            val digest = fingerprints[prior]
            return CodeModeHistoryAnchor(digest, fingerprints.take(prior).count { it == digest }, boundary - prior - 1)
        }
        return CodeModeReplayAnchors(at(items.size), natives.associate { it.logicalOffset to at(it.logicalOffset) })
    }
}

/** One index over client items is shared by all v5 record placements in a rewrite. */
private val OWNED_TYPES = setOf("function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output")

internal class CodeModeHistoryIndex(
    val items: List<JsonElement>,
    private val codec: CodexCodeModeHistoryCodec,
) {
    private val ids = mutableMapOf<String, MutableList<Int>>()
    private val anchors = mutableMapOf<String, MutableList<Int>>()

    init {
        items.forEachIndexed { index, item ->
            val type = codec.string(item as? JsonObject, "type")
            if (type in OWNED_TYPES) codec.callId(item)?.let { ids.getOrPut(it) { mutableListOf() } += index }
            anchors.getOrPut(InputDigest.hex(item)) { mutableListOf() } += index
        }
    }

    fun resolve(anchor: CodeModeHistoryAnchor, before: Int = items.size): Int? {
        if (anchor.occurrence < 0 || anchor.logicalTail < 0) return null
        val candidates = anchors[anchor.itemDigest].orEmpty()
        val at = candidates.binarySearch(before)
        val last = if (at >= 0) at - 1 else -at - 2
        val prior = when {
            anchor.itemDigest == null -> 0
            last < 0 -> null
            else -> candidates[minOf(anchor.occurrence, last)] + 1
        }
        return prior?.let { (it + anchor.logicalTail).takeIf { end -> end <= items.size } ?: it }
    }

    fun owned(record: CodeModeRecord): List<Int> =
        (record.clientIds() + record.outerCallId).flatMap { ids[it].orEmpty() }.sorted()

    fun boundary(record: CodeModeRecord): Int? {
        val anchor = record.replayAnchors?.baseline ?: return null
        val owned = owned(record)
        val before = owned.firstOrNull() ?: items.size
        val prior = resolve(anchor.copy(logicalTail = 0), before) ?: return null
        val expected = checkNotNull(resolve(anchor, before))
        val first = owned.firstOrNull()
        return when {
            owned.any { it < prior } -> null
            first == null -> afterParent(record, expected)
            else -> beforeCallback(record, prior, expected, first)
        }
    }

    private fun beforeCallback(record: CodeModeRecord, prior: Int, expected: Int, first: Int): Int {
        val start = first - record.continuity.size
        val present = start >= prior && codec.continuityAt(items, start, record.continuity)
        return minOf(expected, if (present) start else first)
    }

    private fun afterParent(record: CodeModeRecord, expected: Int): Int {
        val parent = record.nativeParent ?: return expected
        val at = owned(parent).lastOrNull() ?: return expected
        return maxOf(expected, at + 1 + parent.accepted.durableMedia().size)
    }
}
