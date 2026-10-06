// NEW: native ownership is positional and counted; shared ancestors contribute each payload only once.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.provider.codex.state.diagnostics.CodeModeNativeEvidence
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.provider.codex.state.native.CodeModeCapturedOrder

private data class NativeClaim(
    val recordId: String,
    val offset: Int,
    val items: List<JsonElement>,
    val following: Boolean,
    val evidence: CodeModeNativeEvidence? = null,
)

internal data class CodeModeNativeOrigin(val record: CodeModeRecord, val segment: CodeModeNativeSegment)

internal class CodeModeNativeReplay(
    private val codec: CodexCodeModeHistoryCodec,
    private val input: ResponsesCodeModeInput,
    private val index: CodeModeHistoryIndex,
    records: List<CodeModeRecord>,
) {
    private val bad = mutableMapOf<String, CodeModeNativeRejection>()
    private val origins = ancestors(records).flatMap { record ->
        record.continuityReplay.map { CodeModeNativeOrigin(record, it) }
    }
    private val replay = input.replayItems.groupBy { it.logicalOffset }.mapValues { (_, segments) ->
        segments.flatMap { it.items }
    }
    private val nativeReplay = input.nativeSegments.groupBy { it.logicalOffset }.mapValues { (_, segments) ->
        segments.flatMap { it.items }
    }

    init {
        val claims = claims(records)
        val replayed = replay.mapValues { (_, items) -> items.groupBy(::identity) }
        claims.forEach { claim ->
            claim.items.forEach { expected ->
                val actual = replayed[claim.offset]?.get(identity(expected))
                if (actual?.any { it != expected } == true) {
                    bad.putIfAbsent(
                        claim.recordId,
                        CodeModeNativeRejection(claim.following, CodeModeNativeBranch.PAYLOAD, claim.evidence),
                    )
                }
            }
        }
        val unexpected = unexpectedOffset(records, claims)
        unexpected?.let { segment ->
            records.filter { (index.boundary(it) ?: 0) > segment.logicalOffset }.forEach { record ->
                val parentFailure = generateSequence(record.nativeParent) { it.nativeParent }
                    .firstNotNullOfOrNull { bad[it.id] }
                bad.putIfAbsent(record.id, parentFailure ?: unexpectedFailure(record, segment, claims))
            }
        }
        val nodes = ancestors(records)
        val children = nodes.groupBy { it.nativeParent?.id }
        val pending = ArrayDeque(bad.keys)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            children[parent].orEmpty().forEach { child ->
                if (bad.putIfAbsent(child.id, bad.getValue(parent)) == null) pending += child.id
            }
        }
    }

    private fun unexpectedFailure(
        record: CodeModeRecord,
        segment: ResponsesCodeModeReplay,
        claims: List<NativeClaim>,
    ): CodeModeNativeRejection {
        val matched = claims.filter { claim -> claim.items.any { it in segment.items } }
        val witness = matched.map(NativeClaim::following).distinct().singleOrNull()
        val expected = claims.distinctBy { it.offset to it.items }.sumOf { claim ->
            claim.items.count { it in segment.items }
        }
        val boundary = index.boundary(record) ?: 0
        val actual = replay.filterKeys { it < boundary }.values.sumOf { items -> items.count { it in segment.items } }
        val evidence = matched.map(NativeClaim::evidence).distinct().singleOrNull()
            ?.copy(expectedOccurrences = expected, actualOccurrences = actual)
        // A restored parent may be missing; surviving claims are not a complete history denominator.
        return CodeModeNativeRejection(witness, CodeModeNativeBranch.UNEXPECTED, evidence)
    }

    private fun unexpectedOffset(records: List<CodeModeRecord>, claims: List<NativeClaim>): ResponsesCodeModeReplay? {
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
        return input.nativeSegments.firstNotNullOfOrNull { segment ->
            segment.items.firstOrNull { it !in allowed[segment.logicalOffset].orEmpty() }
                ?.let { segment.copy(items = listOf(it)) }
        }
    }

    fun rejection(record: CodeModeRecord, reason: String): CodeModeNativeRejection? =
        bad[record.id]?.takeIf { reason == problem(record) }

    fun problem(record: CodeModeRecord): String? = bad[record.id]?.branch?.reason()

    /** A response-only rewrite cannot become evidence for resurrecting its missing captured input. */
    fun retainedResponse(record: CodeModeRecord): Boolean {
        if (
            record.continuityReplay.isEmpty() ||
            index.owned(record).any { codec.callId(index.items[it]) == record.outerCallId }
        ) {
            return false
        }
        val boundary = index.boundary(record) ?: return false
        val echoed = record.continuityReplay.all { segment ->
            val items = replay[boundary + segment.logicalOffset].orEmpty()
            (0..items.size - segment.items.size).any { at ->
                segment.items.indices.all { items[at + it] == segment.items[it] }
            }
        }
        val baseline = CodeModeNativeChain.replay(record).flatMap(CodeModeNativeSegment::items)
        return echoed && baseline.isNotEmpty() && replay.values.none { items -> items.any { it in baseline } }
    }

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
        order: CodeModeCapturedOrder? = null,
        refused: CodeModeCapturedOrder? = null,
    ): List<ResponsesCodeModeReplay> {
        val scripts = placements.filter { it.emission == CodeModeCanonicalEmission.SCRIPT }
        val retained = placements.filter { it.emission == CodeModeCanonicalEmission.CONTINUITY }
            .map { it.record.id }.toSet()
        val claims = claims(scripts.map { it.record }).filterNot { it.recordId in retained }
            .distinctBy { it.offset to it.items }
        val ownedIds = scripts.flatMap { it.record.clientIds() - it.retainedCallbacks }.toSet()
        val counted = mutableMapOf<Int, MutableMap<JsonElement, Int>>()
        claims.forEach { claim -> count(counted, claim.offset, claim.items) }
        placements.forEach { placement ->
            // Copy quotas refer to the observed history, not the plan's relocated emission buckets.
            val observed = checkNotNull(index.boundary(placement.record))
            placement.record.continuityReplay.forEach {
                count(counted, observed + it.logicalOffset, it.items)
            }
        }
        val replay = clientReplay(ownedIds, counted, offsets).toMutableList()
        val placed = scripts.associateBy { it.record.id }
        claims.forEach { claim ->
            val placement = placed[claim.recordId]
            val offset = if (placement != null && claim.offset == placement.boundary) {
                starts.getValue(claim.recordId)
            } else {
                offsets[claim.offset]
            }
            val items = claimItems(claim, order, refused)
            if (items.isNotEmpty()) replay += ResponsesCodeModeReplay(offset, null, items)
        }
        return replay
    }

    private fun claimItems(
        claim: NativeClaim,
        order: CodeModeCapturedOrder?,
        refused: CodeModeCapturedOrder?,
    ): List<JsonElement> = order?.claim(claim.items) ?: refused?.claimAt(claim.offset, claim.items) ?: claim.items

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
        val placement = index.nativeOffset(record, source, segment, replay, origins, nativeReplay)
        val at = placement.offset
        if (at == null) {
            bad.putIfAbsent(
                record.id,
                CodeModeNativeRejection(placement.following, checkNotNull(placement.branch), placement.evidence),
            )
            return null
        }
        return NativeClaim(record.id, at, segment.items, placement.following, placement.evidence)
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
