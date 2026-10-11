// NEW: v5 placements use local history anchors and minted ids instead of canonical prefix digests.
package splice.provider.codex.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonElementInterner
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.provider.codex.state.diagnostics.CodeModeNativeEvidenceCapture
import splice.provider.codex.state.diagnostics.CodeModeNativePosition
import splice.provider.codex.state.native.CodeModeAnchorCapture
import splice.provider.codex.state.native.CodeModeNativeMatching
import splice.provider.codex.state.native.CodeModeNativeScope

@Serializable
internal data class CodeModeHistoryAnchor(val itemDigest: String?, val occurrence: Int, val logicalTail: Int = 0)

@Serializable
internal data class CodeModeReplayAnchors(
    val baseline: CodeModeHistoryAnchor,
    val native: Map<Int, CodeModeHistoryAnchor>,
    val nativeFollowing: Map<Int, CodeModeHistoryAnchor> = emptyMap(),
)

/** One index over client items is shared by all v5 record placements in a rewrite. */
private val OWNED_TYPES = setOf("function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output")

internal class CodeModeHistoryIndex(
    val items: List<JsonElement>,
    private val codec: CodexCodeModeHistoryCodec,
) {
    val payloads = JsonElementInterner()
    private val matching = CodeModeNativeMatching(payloads)
    private val ids = mutableMapOf<String, MutableList<Int>>()
    private val anchors = mutableMapOf<String, MutableList<Int>>()

    init {
        val opaque = mutableSetOf<String>()
        InputDigest.hexItems(items).forEachIndexed { index, digest ->
            val item = items[index]
            val type = codec.string(item as? JsonObject, "type")
            if (type in OWNED_TYPES) codec.callId(item)?.let { ids.getOrPut(it) { mutableListOf() } += index }
            anchors.getOrPut(digest) { mutableListOf() } += index
            CodeModeAnchorCapture.opaqueKey(item, codec)?.let { key ->
                opaque += key
                if (key != digest) anchors.getOrPut(key) { mutableListOf() } += index
            }
        }
        opaque.filter { anchors[it]?.size != 1 }.forEach(anchors::remove)
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
        placed: CodeModeNativeOrigin,
        source: CodeModeRecord,
        replay: Map<Int, List<JsonElement>>,
        origins: List<CodeModeNativeOrigin>,
        nativeReplay: Map<Int, List<JsonElement>> = replay,
    ): CodeModeNativePosition {
        val (record, segment) = placed
        val witness = source.replayAnchors?.nativeFollowing?.get(segment.logicalOffset)
            ?: record.replayAnchors?.nativeFollowing?.get(segment.logicalOffset)
        val following = witness != null
        val anchor = nativeAnchor(record, source, segment.logicalOffset)
        val bounds = nativeBounds(record, source, anchor)
        val scope = CodeModeNativeScope(source, bounds, this)
        val historical = scope.actual(replay, nativeReplay)
        val expectedItems = scope.expected.flatMap(CodeModeNativeSegment::items).map(payloads::token)
        val actualItems = historical.toSortedMap().filterKeys { it in bounds }.values.flatten().map(payloads::token)
        val known = expectedItems.toSet()
        val evidence = CodeModeNativeEvidenceCapture.capture(this, source, record, segment, bounds).copy(
            expectedOccurrences = expectedItems.size,
            actualOccurrences = actualItems.count { it in known },
        )
        if (!matching.ordered(expectedItems, actualItems, known)) {
            return CodeModeNativePosition(null, following, scope.orderFailure, evidence)
        }
        val adjacent = adjacentNativeOffset(placed, source, scope, historical)
        if (adjacent != null) return adjacent
        val actual = historical.filter { (offset, items) ->
            offset in bounds && matching.contains(items, segment.items)
        }.keys.sorted()
        val expected = scope.expected.filter { matching.contains(it.items, segment.items) }
            .map(CodeModeNativeSegment::logicalOffset).distinct().sorted()
        val absent = if (actual.isEmpty()) nativeOwner(segment, origins, bounds) else null
        val at = countedNative(record, source, segment, expected, actual) ?: absent
        val branch = if (actual.isEmpty()) CodeModeNativeBranch.ABSENT else CodeModeNativeBranch.COUNT
        return CodeModeNativePosition(at, following, branch.takeIf { at == null }, evidence)
    }

    /** An absent native can be placed by the earlier script that produced it, never by a canonical tail. */
    private fun nativeOwner(
        segment: CodeModeNativeSegment,
        origins: List<CodeModeNativeOrigin>,
        bounds: IntRange,
    ): Int? = origins.filter { matching.contains(segment.items, it.segment.items) && owned(it.record).isNotEmpty() }
        .mapNotNull { origin ->
            boundary(origin.record)?.plus(origin.segment.logicalOffset)?.takeIf { it in bounds }
        }.distinct().singleOrNull()

    private fun adjacentNativeOffset(
        placed: CodeModeNativeOrigin,
        source: CodeModeRecord,
        scope: CodeModeNativeScope,
        replay: Map<Int, List<JsonElement>>,
    ): CodeModeNativePosition? {
        val (record, segment) = placed
        val offset = segment.logicalOffset
        val before = nativeAnchor(record, source, offset)
        val after = source.replayAnchors?.nativeFollowing?.get(offset)
            ?: record.replayAnchors?.nativeFollowing?.get(offset)
        val at = before?.takeIf { it.logicalTail == 0 }?.let { resolve(it) }
            ?: after?.let { resolve(it)?.minus(1) } ?: return null
        val items = (scope.expected.firstOrNull { it.logicalOffset == offset }?.items ?: segment.items)
            .map(payloads::token)
        val actual = replay[at].orEmpty().map(payloads::token)
        val known = items.toSet()
        val evidence = CodeModeNativeEvidenceCapture.capture(
            this,
            source,
            record,
            segment,
            nativeBounds(record, source, before),
        ).copy(expectedOccurrences = items.size, actualOccurrences = actual.count { it in known })
        val valid = matching.ordered(items, actual, known)
        return CodeModeNativePosition(
            at.takeIf { valid },
            after != null,
            scope.orderFailure.takeUnless { valid },
            evidence,
        )
    }

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
        if (segment.logicalOffset == record.origin.baseline.logicalCount) {
            return continuityEcho(record).firstOrNull() ?: owned(record).firstOrNull() ?: boundary(record)
        }
        val references = source.replayAnchors?.native.orEmpty() +
            listOfNotNull(source.replayAnchors?.baseline?.let { source.origin.baseline.logicalCount to it }).toMap()
        // The immediately following stable item places an absent native before itself, not at a guessed tail.
        return references.firstNotNullOfOrNull { (offset, anchor) ->
            resolve(anchor.copy(logicalTail = 0))?.minus(1)
                ?.takeIf { offset - anchor.logicalTail == segment.logicalOffset + 1 }
        }
    }

    fun owned(record: CodeModeRecord): List<Int> =
        (record.clientIds() + record.origin.outerCallId).flatMap { ids[it].orEmpty() }.sorted()

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
        val start = first - record.carry.continuity.size
        return if (start >= prior && codec.continuityAt(items, start, record.carry.continuity)) {
            start until first
        } else {
            IntRange.EMPTY
        }
    }

    private fun afterParent(record: CodeModeRecord, expected: Int): Int {
        val parent = record.nativeParent ?: return expected
        val callbacks = owned(parent)
        val tail = callbacks.lastOrNull() ?: return expected
        val following = tail + 1 + parent.accepted.durableMedia().size
        return if (expected < following) following else expected
    }
}
