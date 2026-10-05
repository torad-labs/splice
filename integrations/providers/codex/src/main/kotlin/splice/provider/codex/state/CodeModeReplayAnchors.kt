// NEW: v5 placements use local history anchors and minted ids instead of canonical prefix digests.
package splice.provider.codex.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
    /** [inputBoundary] for a request held as text, parsed once. */
    fun inputBoundary(
        bodyJson: String,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeInputBoundary? = inputBoundary(codec.root(bodyJson)?.second, completed, codec)

    /** [input]: the round's parsed input array, or null when the round is not a Responses request. */
    fun inputBoundary(
        input: JsonArray?,
        completed: List<CodeModeRecord>,
        codec: CodexCodeModeHistoryCodec,
    ): CodeModeInputBoundary? {
        if (input == null) return null
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
        val fingerprints = InputDigest.hexItems(items).toList()
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
        InputDigest.hexItems(items).forEachIndexed { index, digest ->
            val item = items[index]
            val type = codec.string(item as? JsonObject, "type")
            if (type in OWNED_TYPES) codec.callId(item)?.let { ids.getOrPut(it) { mutableListOf() } += index }
            anchors.getOrPut(digest) { mutableListOf() } += index
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

    /** A canonical tail is an emission proposal; an observed native needs its own raw-history witness. */
    fun nativeOffset(
        record: CodeModeRecord,
        source: CodeModeRecord,
        segment: CodeModeNativeSegment,
        replay: Map<Int, List<JsonElement>>,
        origins: List<CodeModeNativeOrigin>,
    ): Int? {
        val anchor = nativeAnchor(record, source, segment.logicalOffset)
        val adjacent = anchor?.takeIf { it.logicalTail == 0 }?.let { resolve(it) }
        if (adjacent != null) return adjacent
        val bounds = nativeBounds(record, source, anchor)
        if (!nativeOrder(source, replay, bounds)) return null
        val actual = replay.filter { (offset, items) ->
            offset in bounds && containsNative(items, segment.items)
        }.keys.sorted()
        val expected = nativeExpected(source, bounds).filter { containsNative(it.items, segment.items) }
            .map(CodeModeNativeSegment::logicalOffset).distinct().sorted()
        val absent = if (actual.isEmpty()) nativeOwner(segment, origins, bounds) else null
        return countedNative(record, source, segment, expected, actual) ?: absent
    }

    /** An absent native can be placed by the earlier script that produced it, never by a canonical tail. */
    private fun nativeOwner(
        segment: CodeModeNativeSegment,
        origins: List<CodeModeNativeOrigin>,
        bounds: IntRange,
    ): Int? = origins.filter { containsNative(segment.items, it.segment.items) && owned(it.record).isNotEmpty() }
        .mapNotNull { origin ->
            boundary(origin.record)?.plus(origin.segment.logicalOffset)?.takeIf { it in bounds }
        }.distinct().singleOrNull()

    private fun nativeAnchor(record: CodeModeRecord, source: CodeModeRecord, offset: Int): CodeModeHistoryAnchor? =
        source.replayAnchors?.native?.get(offset) ?: record.replayAnchors?.native?.get(offset)

    private fun nativeBounds(
        record: CodeModeRecord,
        source: CodeModeRecord,
        anchor: CodeModeHistoryAnchor?,
    ): IntRange {
        val upper = owned(record).firstOrNull() ?: owned(source).firstOrNull() ?: items.size
        val lower = anchor?.let { resolve(it.copy(logicalTail = 0), upper) } ?: 0
        return lower..upper
    }

    /** Repeated payloads retain their captured ordinal; extra or incomplete occurrences prove no position. */
    private fun countedNative(
        record: CodeModeRecord,
        source: CodeModeRecord,
        segment: CodeModeNativeSegment,
        expected: List<Int>,
        actual: List<Int>,
    ): Int? {
        if (actual.isNotEmpty()) {
            return actual.takeIf { it.size == expected.size }?.getOrNull(expected.indexOf(segment.logicalOffset))
        }
        if (segment.logicalOffset == record.baselineLogicalCount) {
            return continuityEcho(record).firstOrNull() ?: owned(record).firstOrNull() ?: boundary(record)
        }
        val references = source.replayAnchors?.native.orEmpty() +
            listOfNotNull(source.replayAnchors?.baseline?.let { source.baselineLogicalCount to it }).toMap()
        // The immediately following stable item places an absent native before itself, not at a guessed tail.
        return references.firstNotNullOfOrNull { (offset, anchor) ->
            resolve(anchor.copy(logicalTail = 0))?.minus(1)
                ?.takeIf { offset - anchor.logicalTail == segment.logicalOffset + 1 }
        }
    }

    /** Translate only the captured lower bound whose stable item actually resolved in the raw history. */
    private fun nativeExpected(source: CodeModeRecord, bounds: IntRange): List<CodeModeNativeSegment> {
        val lower = source.replayAnchors?.native.orEmpty().entries.filter { (_, anchor) ->
            resolve(anchor.copy(logicalTail = 0)) == bounds.first
        }.minOfOrNull { (offset, anchor) -> offset - anchor.logicalTail } ?: 0
        return CodeModeNativeChain.replay(source).filter { it.logicalOffset >= lower }
    }

    /** Exact identities keep the source's order even when opaque-to-callback expansion moves their slots. */
    private fun nativeOrder(
        source: CodeModeRecord,
        replay: Map<Int, List<JsonElement>>,
        bounds: IntRange,
    ): Boolean {
        val expected = nativeExpected(source, bounds).flatMap(CodeModeNativeSegment::items)
        val known = expected.toSet()
        val actual = replay.toSortedMap().filterKeys { it in bounds }.values.flatten().filter { it in known }
        val present = actual.toSet()
        return expected.filter { it in present } == actual
    }

    private fun containsNative(items: List<JsonElement>, expected: List<JsonElement>): Boolean =
        expected.isNotEmpty() && (0..(items.size - expected.size)).any { at ->
            expected.indices.all { items[at + it] == expected[it] }
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
            else -> minOf(expected, continuityEcho(record).firstOrNull() ?: first)
        }
    }

    /** The client's authenticated echo is adjacent to its owned callback, not necessarily at emission. */
    fun continuityEcho(record: CodeModeRecord): IntRange {
        val anchor = record.replayAnchors?.baseline
        val first = owned(record).firstOrNull() ?: return IntRange.EMPTY
        val prior = anchor?.let { resolve(it.copy(logicalTail = 0), first) } ?: return IntRange.EMPTY
        val start = first - record.continuity.size
        return if (start >= prior && codec.continuityAt(items, start, record.continuity)) {
            start until first
        } else {
            IntRange.EMPTY
        }
    }

    private fun afterParent(record: CodeModeRecord, expected: Int): Int {
        val parent = record.nativeParent ?: return expected
        val at = owned(parent).lastOrNull() ?: return expected
        return maxOf(expected, at + 1 + parent.accepted.durableMedia().size)
    }
}
