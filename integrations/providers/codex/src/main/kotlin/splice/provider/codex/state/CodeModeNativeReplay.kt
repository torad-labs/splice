// NEW: native ownership is positional and counted; shared ancestors contribute each payload only once.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

private data class NativeClaim(val recordId: String, val offset: Int, val items: List<JsonElement>)

internal data class CodeModeNativeOrigin(val record: CodeModeRecord, val segment: CodeModeNativeSegment)

internal class CodeModeNativeReplay(
    private val codec: CodexCodeModeHistoryCodec,
    private val input: ResponsesCodeModeInput,
    private val index: CodeModeHistoryIndex,
    records: List<CodeModeRecord>,
) {
    private val bad = mutableSetOf<String>()
    private val origins = ancestors(records).flatMap { record ->
        record.continuityReplay.map { CodeModeNativeOrigin(record, it) }
    }
    private val replay = input.replayItems.groupBy { it.logicalOffset }.mapValues { (_, segments) ->
        segments.flatMap { it.items }
    }

    init {
        val claims = claims(records)
        val replayed = replay.mapValues { (_, items) -> items.groupBy(::identity) }
        claims.forEach { claim ->
            claim.items.forEach { expected ->
                val actual = replayed[claim.offset]?.get(identity(expected))
                if (actual?.any { it != expected } == true) bad += claim.recordId
            }
        }
        val unexpected = unexpectedOffset(records, claims)
        unexpected?.let { at ->
            records.filter { (index.boundary(it) ?: 0) > at }.forEach { bad += it.id }
        }
        val nodes = ancestors(records)
        val children = nodes.groupBy { it.nativeParent?.id }
        val pending = ArrayDeque(bad)
        while (pending.isNotEmpty()) {
            children[pending.removeFirst()].orEmpty().forEach { child ->
                if (bad.add(child.id)) pending += child.id
            }
        }
    }

    private fun unexpectedOffset(records: List<CodeModeRecord>, claims: List<NativeClaim>): Int? {
        val allowed = mutableMapOf<Int, MutableSet<JsonElement>>()
        claims.forEach { allowed.getOrPut(it.offset) { mutableSetOf() } += it.items }
        records.forEach { record ->
            val boundary = index.boundary(record)
            if (boundary != null) {
                record.continuityReplay.forEach {
                    allowed.getOrPut(boundary + it.logicalOffset) { mutableSetOf() } += it.items
                }
            }
        }
        return input.nativeSegments.firstOrNull { segment ->
            segment.items.any { it !in allowed[segment.logicalOffset].orEmpty() }
        }?.logicalOffset
    }

    fun problem(record: CodeModeRecord): String? =
        "code-mode native discovery history was edited".takeIf { record.id in bad }

    fun restore(record: CodeModeRecord): ResponsesCodeModeInput {
        val claims = claims(listOf(record)).distinctBy { it.offset to it.items }
        val counted = mutableMapOf<Int, MutableMap<JsonElement, Int>>()
        claims.forEach { count(counted, it.offset, it.items) }
        val replay = clientReplay(emptySet(), counted, IntArray(input.logicalItems.size + 1) { it }).toMutableList()
        claims.forEach { replay += ResponsesCodeModeReplay(it.offset, null, it.items) }
        return input.copy(replayItems = CodeModeNativeChain.emittedReplay(replay))
    }

    fun rewrite(
        placements: List<CodeModeCanonicalPlacement>,
        offsets: IntArray,
        starts: Map<String, Int>,
    ): List<ResponsesCodeModeReplay> {
        val claims = claims(placements.map { it.record }).distinctBy { it.offset to it.items }
        val ownedIds = placements.flatMap { it.record.clientIds() - it.retainedCallbacks }.toSet()
        val counted = mutableMapOf<Int, MutableMap<JsonElement, Int>>()
        claims.forEach { claim -> count(counted, claim.offset, claim.items) }
        placements.forEach { placement ->
            placement.record.continuityReplay.forEach {
                count(counted, placement.boundary + it.logicalOffset, it.items)
            }
        }
        val replay = clientReplay(ownedIds, counted, offsets).toMutableList()
        val placed = placements.associateBy { it.record.id }
        claims.forEach { claim ->
            val placement = placed[claim.recordId]
            val offset = if (placement != null && claim.offset == placement.boundary) {
                starts.getValue(claim.recordId)
            } else {
                offsets[claim.offset]
            }
            replay += ResponsesCodeModeReplay(offset, null, claim.items)
        }
        return replay
    }

    private fun clientReplay(
        ownedIds: Set<String>,
        counted: MutableMap<Int, MutableMap<JsonElement, Int>>,
        offsets: IntArray,
    ): List<ResponsesCodeModeReplay> = input.replayItems.filterNot { it.callbackId in ownedIds }.mapNotNull { segment ->
        val quota = counted[segment.logicalOffset].orEmpty()
        val kept = segment.items.filter { item ->
            val remaining = quota[item] ?: 0
            if (remaining > 0) counted.getValue(segment.logicalOffset)[item] = remaining - 1
            remaining == 0
        }
        kept.takeIf(List<JsonElement>::isNotEmpty)?.let {
            segment.copy(logicalOffset = offsets[segment.logicalOffset], items = it)
        }
    }

    private fun claims(records: List<CodeModeRecord>): List<NativeClaim> {
        val placed = records.map { it.id }.toSet()
        val sources = mutableMapOf<String, CodeModeRecord>()
        records.forEach { source ->
            var cursor: CodeModeRecord? = source
            while (cursor != null && cursor.id !in sources) {
                sources[cursor.id] = source
                cursor = cursor.nativeParent
            }
        }
        return ancestors(records).flatMap { record ->
            val source = sources.getValue(record.id)
            val inherited = if (record.id in placed) emptyList() else CodeModeNativeChain.continuity(record)
            (record.nativeSegments + inherited).mapNotNull { segment -> claim(record, source, segment) }
        }
    }

    private fun claim(record: CodeModeRecord, source: CodeModeRecord, segment: CodeModeNativeSegment): NativeClaim? {
        val at = index.nativeOffset(record, source, segment, replay, origins)
        if (at == null) {
            bad += record.id
            return null
        }
        return NativeClaim(record.id, at, segment.items)
    }

    private fun ancestors(records: List<CodeModeRecord>): List<CodeModeRecord> {
        val indexed = linkedMapOf<String, CodeModeRecord>()
        records.forEach { record ->
            val chain = mutableListOf<CodeModeRecord>()
            var cursor: CodeModeRecord? = record
            while (cursor != null && cursor.id !in indexed) {
                chain += cursor
                cursor = cursor.nativeParent
            }
            chain.asReversed().forEach { indexed[it.id] = it }
        }
        return indexed.values.toList()
    }

    private fun count(target: MutableMap<Int, MutableMap<JsonElement, Int>>, offset: Int, items: List<JsonElement>) {
        val quota = target.getOrPut(offset) { mutableMapOf() }
        items.forEach { quota[it] = (quota[it] ?: 0) + 1 }
    }

    private fun identity(item: JsonElement): Pair<String, String> {
        val objectItem = item as? JsonObject
        return codec.string(objectItem, "type") to
            (codec.callId(item) ?: codec.string(objectItem, "id"))
    }
}
