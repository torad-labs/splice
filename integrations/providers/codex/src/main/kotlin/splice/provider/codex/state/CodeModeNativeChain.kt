// NEW: native history is stored as per-record additions and reconstructed along the record chain.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonElementInterner
import splice.core.util.JsonElementInterner.Token
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot

internal object CodeModeNativeChain {
    data class Capture(val segments: List<CodeModeNativeSegment>, val parent: CodeModeRecord?)

    fun capture(current: List<CodeModeNativeSegment>, prior: CodeModeRecord?): Capture {
        val projected = normalized(current)
        if (prior == null) return Capture(projected, null)
        val inherited = normalized(replay(prior) + continuity(prior)).associateBy(CodeModeNativeSegment::logicalOffset)
        val indexed = projected.associateBy(CodeModeNativeSegment::logicalOffset)
        val compatible = inherited.all { (offset, segment) ->
            startsWith(indexed[offset]?.items.orEmpty(), segment.items)
        }
        if (!compatible) return Capture(projected, null)
        val additions = projected.mapNotNull { segment ->
            val before = inherited[segment.logicalOffset]?.items.orEmpty()
            val delta = segment.items.drop(before.size)
            delta.takeIf(List<JsonElement>::isNotEmpty)?.let { segment.copy(items = it) }
        }
        return Capture(additions, prior)
    }

    /** Only earlier records of the same conversation can be a parent. Missing parents stay unknown. */
    fun link(records: List<CodeModeRecord>) {
        val prior = mutableMapOf<Pair<String, String>, CodeModeRecord>()
        records.forEach { record ->
            record.nativeParent = record.nativeBaseId?.let { prior[record.key to it] }
            prior[record.key to record.id] = record
        }
    }

    fun replay(record: CodeModeRecord): List<CodeModeNativeSegment> {
        val chain = mutableListOf<CodeModeRecord>()
        var cursor: CodeModeRecord? = record
        while (cursor != null) {
            chain += cursor
            cursor = cursor.nativeParent
        }
        val segments = chain.asReversed().flatMap { ancestor ->
            ancestor.nativeSegments + if (ancestor === record) emptyList() else continuity(ancestor)
        }
        return normalized(segments)
    }

    /** A retention checkpoint transfers inherited payload to the first surviving child, once. */
    fun snapshot(record: CodeModeRecord, retained: Set<String>): CodeModeRecordSnapshot {
        val state = record.snapshot()
        val parent = record.nativeParent
        return if (parent == null || parent.id in retained) {
            state
        } else {
            state.copy(nativeSegments = replay(record)).also {
                it.issued = state.issued
                it.sessionId = state.sessionId
                it.conversationId = state.conversationId
                it.replayAnchors = state.replayAnchors
                it.sourceState = state.sourceState
            }
        }
    }

    /** Publish a newly independent root only after its checkpoint was forced successfully. */
    fun publishRoot(record: CodeModeRecord, state: CodeModeRecordSnapshot) {
        if (record.nativeBaseId != state.nativeBaseId) {
            record.nativeSegments = state.nativeSegments
            record.nativeBaseId = state.nativeBaseId
            record.nativeParent = null
        }
    }

    fun continuity(record: CodeModeRecord): List<CodeModeNativeSegment> =
        record.continuityReplay.map { it.copy(logicalOffset = record.baselineLogicalCount + it.logicalOffset) }

    /** Adjacent replay fragments at one logical slot are one sequence, irrespective of parser grouping. */
    fun normalized(segments: List<CodeModeNativeSegment>): List<CodeModeNativeSegment> =
        segments.groupBy(CodeModeNativeSegment::logicalOffset).toSortedMap().map { (offset, fragments) ->
            CodeModeNativeSegment(offset, fragments.flatMap(CodeModeNativeSegment::items))
        }

    fun normalizedReplay(replay: List<ResponsesCodeModeReplay>): List<ResponsesCodeModeReplay> =
        replay.groupBy { it.logicalOffset to it.callbackId }.map { (_, fragments) ->
            fragments.first().copy(items = fragments.flatMap(ResponsesCodeModeReplay::items))
        }.sortedBy(ResponsesCodeModeReplay::logicalOffset)

    /**
     * The replay a rewrite emits: [normalizedReplay], with an item that several owners hold at one slot
     * emitted once. A record captured from a body splice posted, whose slots no longer line up
     * with its predecessor's, is a root that holds the whole native history, including the reasoning an
     * earlier record emits as its own continuity. Each owner re-emitting its copy put the same reasoning
     * at one slot twice, the next root captured both, and a live conversation's posts doubled per script
     * to 38 MB. Upstream never produces one item twice, so a repeat at a slot is always a copy; the same
     * bytes at another slot are the client's own history and stay. A slot's natives come before its
     * callback replay, which is the order the request is rebuilt in.
     */
    fun emittedReplay(
        replay: List<ResponsesCodeModeReplay>,
        payloads: JsonElementInterner = JsonElementInterner(),
    ): List<ResponsesCodeModeReplay> {
        val seen = mutableMapOf<Int, MutableSet<Token>>()
        return normalizedReplay(replay).sortedBy { it.callbackId != null }.sortedBy { it.logicalOffset }.mapNotNull {
            val slot = seen.getOrPut(it.logicalOffset) { mutableSetOf() }
            it.copy(items = it.items.filter { item -> slot.add(payloads.token(item)) })
                .takeIf { segment -> segment.items.isNotEmpty() }
        }
    }

    fun allowed(record: CodeModeRecord): Set<Pair<Int, List<JsonElement>>> {
        val baseline = replay(record)
        val continuity = continuity(record)
        return (baseline + continuity + normalized(baseline + continuity))
            .map { it.logicalOffset to it.items }.toSet()
    }

    /** Remove only this script's known continuity suffix, retaining natives that share its slot. */
    fun withoutContinuity(
        replay: List<ResponsesCodeModeReplay>,
        record: CodeModeRecord,
    ): List<ResponsesCodeModeReplay> {
        val continuity = normalized(continuity(record)).associateBy(CodeModeNativeSegment::logicalOffset)
        return normalizedReplay(replay).mapNotNull { segment ->
            val owned = continuity[segment.logicalOffset]?.items.orEmpty()
            val first = segment.items.size - owned.size
            val suffix = first >= 0 && segment.items.subList(first, segment.items.size) == owned
            val items = if (suffix) segment.items.take(first) else segment.items
            items.takeIf(List<JsonElement>::isNotEmpty)?.let { segment.copy(items = it) }
        }
    }

    fun startsWith(items: List<JsonElement>, prefix: List<JsonElement>): Boolean =
        items.size >= prefix.size && items.subList(0, prefix.size) == prefix
}
