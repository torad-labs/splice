// NEW: a selected request capture pins posted bundle order and counted native occurrences at emission.
package splice.provider.codex.state.native

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeAnchorCapture
import splice.provider.codex.state.CodeModeCanonicalEmission
import splice.provider.codex.state.CodeModeCanonicalPlacement
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeNativeChain
import java.util.IdentityHashMap

/** Captured coordinates are translated through stable witnesses, never compared with raw callback slots. */
internal class CodeModeCapturedOrder(
    private val capture: CodeModeRecord,
    private val index: CodeModeHistoryIndex,
    private val codec: CodexCodeModeHistoryCodec,
) {
    private val segments = CodeModeNativeChain.replay(capture)
    private val native = CodeModeCapturedOccurrences(segments, codec)
    private val pins = mutableMapOf<String, Pair<Int, Int>>()
    private var refusedBounds = IntRange.EMPTY
    private var refused = emptyMap<Int, List<JsonElement>>()

    fun support(records: List<CodeModeRecord>, placed: List<CodeModeCanonicalPlacement>) {
        native.support(records, placed)
    }

    /** Missing copies can be restored; an extra copy or an observed permutation cannot be healed. */
    fun accepts(input: ResponsesCodeModeInput): Boolean {
        val first = segments.firstOrNull() ?: return true
        val anchor = capture.replayAnchors?.native?.get(first.logicalOffset)
        val upper = index.owned(capture).firstOrNull() ?: index.items.size
        val lower = anchor?.let { index.resolve(it.copy(logicalTail = 0), upper) } ?: 0
        val scope = CodeModeNativeScope(capture, lower..upper, index)
        val replay = input.replayItems.groupBy { it.logicalOffset }.mapValues { (_, fragments) ->
            fragments.flatMap { it.items }
        }
        val nativeReplay = input.nativeSegments.groupBy { it.logicalOffset }.mapValues { (_, fragments) ->
            fragments.flatMap { it.items }
        }
        val expected = scope.expected.flatMap { it.items }
        val observed = scope.actual(replay, nativeReplay).toSortedMap().filterKeys { it in lower..upper }
        val actual = observed.values.flatten()
        val accepted = native.compatible(actual) && native.subsequence(expected, actual.filter(native::contains))
        if (!accepted) {
            // Only this captured comparison span retains evidence; ordinary logical rewrites still run.
            refusedBounds = lower..upper
            refused = observed.mapValues { (_, items) -> items.filter(native::recognizes) }
        }
        return accepted
    }

    fun placements(placements: List<CodeModeCanonicalPlacement>): List<CodeModeCanonicalPlacement> {
        placements.forEach { placement -> position(placement.record)?.let { pins[placement.record.id] = it } }
        val regions = pins.values.map { it.first }.toSet()
        return placements.map { placement ->
            val pin = pins[placement.record.id]
            val prior = placement.record.replayAnchors?.baseline?.let { index.resolve(it.copy(logicalTail = 0)) }
            placement.copy(boundary = pin?.first ?: prior?.takeIf { it in regions } ?: placement.boundary)
        }
    }

    fun ordered(placements: List<CodeModeCanonicalPlacement>): List<CodeModeCanonicalPlacement> {
        val pinned = placements.filter { it.record.id in pins }.sortedBy { pins.getValue(it.record.id).second }
        if (pinned.isEmpty()) return placements
        val remaining = ArrayDeque(
            placements.filter { it.record.id !in pins }.sortedBy {
                index.boundary(it.record) ?: index.items.size
            },
        )
        val ordered = mutableListOf<CodeModeCanonicalPlacement>()
        var tail = 0
        for (placement in pinned) {
            val expected = pins.getValue(placement.record.id).second
            while (tail < expected && remaining.isNotEmpty()) {
                val prior = remaining.removeFirst()
                ordered += prior
                tail += prior.logicalSize
            }
            ordered += placement
            tail += placement.logicalSize
        }
        ordered += remaining
        return ordered
    }

    private fun position(record: CodeModeRecord): Pair<Int, Int>? {
        val key = CodeModeAnchorCapture.opaqueKey(CodeModeCallReplay.item(record), codec)
        val following = capture.replayAnchors?.nativeFollowing.orEmpty().entries
            .filter { (_, anchor) -> key != null && anchor.itemDigest == key }
            .map { it.key - record.continuity.size }.distinct().singleOrNull()
        val matched = following ?: native.positions(record).distinct().singleOrNull() ?: return null
        val segment = segments.firstOrNull { it.logicalOffset >= matched }
        return segment?.let { native ->
            capture.replayAnchors?.native?.get(native.logicalOffset)?.let { anchor ->
                index.resolve(anchor.copy(logicalTail = 0))?.let { at ->
                    at to anchor.logicalTail - (native.logicalOffset - matched)
                }
            }
        }
    }

    fun prefixEnd(offsets: IntArray): Int? = capture.replayAnchors?.baseline?.let { anchor ->
        index.resolve(anchor.copy(logicalTail = 0))?.let { offsets[it] + anchor.logicalTail }
    }

    fun native(offsets: IntArray): List<ResponsesCodeModeReplay> = segments.mapNotNull { segment ->
        val anchor = capture.replayAnchors?.native?.get(segment.logicalOffset) ?: return@mapNotNull null
        val prior = index.resolve(anchor.copy(logicalTail = 0)) ?: return@mapNotNull null
        val items = segment.items.filter(native::supported)
        items.takeIf(List<JsonElement>::isNotEmpty)?.let {
            ResponsesCodeModeReplay(offsets[prior] + anchor.logicalTail, null, it)
        }
    }

    fun continuity(record: CodeModeRecord, start: Int, prefixEnd: Int): List<ResponsesCodeModeReplay> =
        record.continuityReplay.mapNotNull { segment ->
            val added = segment.items.filterNot(native::contains)
            added.takeIf(List<JsonElement>::isNotEmpty)?.let {
                ResponsesCodeModeReplay(maxOf(start + segment.logicalOffset, prefixEnd), null, it)
            }
        }

    fun claim(items: List<JsonElement>): List<JsonElement> = items.filterNot(native::contains)

    fun claimAt(offset: Int, items: List<JsonElement>): List<JsonElement> =
        if (offset in refusedBounds) items.filterNot(native::recognizes) else items

    fun preserved(offsets: IntArray): List<ResponsesCodeModeReplay> = refused.mapNotNull { (offset, items) ->
        items.takeIf(List<JsonElement>::isNotEmpty)?.let { ResponsesCodeModeReplay(offsets[offset], null, it) }
    }
}

/** One digest pass indexes counted occurrences; equality is checked only inside a digest bucket. */
private class CodeModeCapturedOccurrences(
    segments: List<CodeModeNativeSegment>,
    private val codec: CodexCodeModeHistoryCodec,
) {
    private data class Occurrence(val offset: Int, val ordinal: Int, val item: JsonElement)
    private val digests = IdentityHashMap<JsonElement, String>()
    private val slots = segments.associate { it.logicalOffset to it.items }
    private val occurrences = segments.flatMap { segment ->
        segment.items.mapIndexed { ordinal, item -> Occurrence(segment.logicalOffset, ordinal, item) }
    }.groupBy { digest(it.item) }
    private val identities = segments.flatMap(CodeModeNativeSegment::items).mapNotNull(::identity).toSet()
    private val producers = mutableMapOf<String, MutableList<Pair<CodeModeRecord, JsonElement>>>()
    private val available = mutableSetOf<String>()

    private fun digest(item: JsonElement): String = digests.getOrPut(item) { InputDigest.hex(item) }

    private fun matches(actual: JsonElement?, expected: JsonElement): Boolean {
        val value = actual ?: return false
        return digest(value) == digest(expected) && value == expected
    }

    fun contains(item: JsonElement): Boolean = occurrences[digest(item)].orEmpty().any { it.item == item }

    private fun identity(item: JsonElement): Pair<String, String>? = (item as? JsonObject)?.let { value ->
        (codec.string(value, "id").takeUnless(String::isEmpty) ?: codec.callId(value))
            ?.let { codec.string(value, "type") to it }
    }

    fun recognizes(item: JsonElement): Boolean = identity(item) in identities || contains(item)

    fun compatible(items: List<JsonElement>): Boolean =
        items.all { identity(it) !in identities || contains(it) }

    fun supported(item: JsonElement): Boolean {
        val owners = producers[digest(item)].orEmpty().filter { it.second == item }
        return owners.isEmpty() || owners.any { it.first.id in available }
    }

    fun support(records: List<CodeModeRecord>, placed: List<CodeModeCanonicalPlacement>) {
        val seen = mutableSetOf<String>()
        records.forEach { source ->
            generateSequence(source) { it.nativeParent }.takeWhile { seen.add(it.id) }.forEach { record ->
                record.continuityReplay.flatMap { it.items }.forEach { item ->
                    producers.getOrPut(digest(item)) { mutableListOf() } += record to item
                }
            }
        }
        placed.forEach { placement ->
            available += placement.record.id
            if (placement.emission == CodeModeCanonicalEmission.SCRIPT) {
                generateSequence(placement.record.nativeParent) { it.nativeParent }
                    .takeWhile { it.id !in available }.forEach { available += it.id }
            }
        }
    }

    fun subsequence(expected: List<JsonElement>, actual: List<JsonElement>): Boolean {
        val positions = expected.mapIndexed { at, item -> at to item }.groupBy { digest(it.second) }
        var cursor = 0
        for (item in actual) {
            val next = positions[digest(item)].orEmpty().firstOrNull { it.first >= cursor && it.second == item }
                ?: return false
            cursor = next.first + 1
        }
        return true
    }

    fun positions(record: CodeModeRecord): List<Int> = record.continuityReplay.flatMap { segment ->
        val first = segment.items.firstOrNull() ?: return@flatMap emptyList()
        occurrences[digest(first)].orEmpty().filter { occurrence ->
            val items = slots.getValue(occurrence.offset)
            segment.items.indices.all { delta ->
                matches(items.getOrNull(occurrence.ordinal + delta), segment.items[delta])
            }
        }.map { it.offset - segment.logicalOffset }
    }
}
