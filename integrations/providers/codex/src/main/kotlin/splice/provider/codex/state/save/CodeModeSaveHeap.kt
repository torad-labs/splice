// NEW: snapshot preparation and encoding reserve their peaks before allocating or writing state.
package splice.provider.codex.state.save

import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapWeights
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.CodeModeHeap
import splice.provider.codex.state.CodeModeKeptState
import java.util.IdentityHashMap

// why: mutable snapshot collections coexist with their immutable copies before durable publication.
private const val SNAPSHOT_PEAK_FACTOR = 2L

// why: escaped field text, envelope builders, UTF-16 copies and UTF-8 write buffers coexist.
private const val ENCODING_PEAK_FACTOR = 16L

// why: per-key preparation maps, temporary references and empty envelopes still allocate metadata.
private const val SAVE_METADATA_BYTES = 4096L

// why: aggregate references and temporary keyed maps survive apart from snapshot payloads.
private const val SAVE_REFERENCE_BYTES = 128L

internal class CodeModeSaveHeap(private val heap: HeapBudget) {
    /** Changed-field encoding grows to a checkpoint only after its exact append decision is known. */
    inner class Encoding(private val peak: HeapLease) : AutoCloseable {
        fun checkpoint(prior: CodeModeKeptState, cells: List<CodeModeRecordSnapshot>) {
            var bytes = markerBytes(prior.expired)
            prior.records.values.forEach { old ->
                bytes = HeapJson.add(bytes, CodeModeHeap.bytes(cells.firstOrNull { it.id == old.id } ?: old))
            }
            cells.filter { it.id !in prior.records }.forEach { bytes = HeapJson.add(bytes, CodeModeHeap.bytes(it)) }
            val required = HeapWeights.multiply(bytes, ENCODING_PEAK_FACTOR)
            if (!peak.resize(maxOf(peak.bytes, required))) throw HeapCapacityException()
        }

        /** A writer may retain text. Only that string's charge survives this temporary stage. */
        fun retain(text: String) {
            HeapOwners.keep(text, peak.split(HeapJson.text(text)))
        }

        override fun close() {
            peak.close()
        }
    }

    fun cells(current: CodeModeRecord, indexed: CodeModeKeptState): HeapLease {
        var bytes = HeapJson.add(SAVE_METADATA_BYTES, CodeModeHeap.bytes(current))
        indexed.dirty.values.forEach { record ->
            if (record.id != current.id) bytes = HeapJson.add(bytes, CodeModeHeap.bytes(record))
        }
        return reserve(bytes, SNAPSHOT_PEAK_FACTOR)
    }

    fun snapshot(
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        key: String,
        indexed: CodeModeKeptState?,
    ): HeapLease {
        var bytes = SAVE_METADATA_BYTES
        records.forEach { if (it.key == key) bytes = HeapJson.add(bytes, snapshotBytes(it, records)) }
        expired.forEach { if (it.key == key) bytes = HeapJson.add(bytes, CodeModeHeap.bytes(it)) }
        bytes = HeapJson.add(bytes, indexed?.records?.size?.toLong()?.times(SAVE_REFERENCE_BYTES) ?: 0L)
        return reserve(bytes, SNAPSHOT_PEAK_FACTOR)
    }

    /** A surviving child materializes inherited native payload when its parent leaves this checkpoint. Its peak is
     *  returned for the reservation, and the live child is charged what the inherited payload occupies. */
    private fun snapshotBytes(record: CodeModeRecord, retained: List<CodeModeRecord>): Long {
        val bytes = CodeModeHeap.bytes(record)
        val parent = record.nativeParent ?: return bytes
        if (retained.any { it.key == record.key && it.id == parent.id }) return bytes
        val inherited = mutableListOf<CodeModeNativeSegment>()
        var ancestor: CodeModeRecord? = parent
        while (ancestor != null) {
            val current = ancestor
            inherited += current.nativeSegments + current.continuityReplay
            ancestor = current.nativeParent
        }
        // The live child survives independently of the durable snapshot after publishRoot.
        CodeModeHeap.own(record, heap, inherited)
        return inherited.fold(bytes) { total, segment -> HeapJson.add(total, CodeModeHeap.bytes(segment)) }
    }

    /** Partition prepared graphs before any durable write, with no uncharged handoff interval. */
    fun retain(item: CodeModeSaveSnapshots.Prepared, peak: HeapLease) {
        val state = item.conversation
        val records = state?.records ?: item.cells.map(CodeModeSaveSnapshots.Cell::snapshot)
        val sources = IdentityHashMap<CodeModeRecordSnapshot, CodeModeRecord>()
        item.sources.forEach { sources[it.snapshot] = it.live }
        records.filter { it.heapLease == null }.forEach { charge(it, sources[it], peak) }
        state?.expired.orEmpty().forEach { marker ->
            if (marker.heapLease == null) {
                marker.heapLease = peak.split(CodeModeHeap.bytes(marker)).also { HeapOwners.keep(marker, it) }
            }
        }
        if (state != null) {
            val references = (state.records.size + state.expired.size) * SAVE_REFERENCE_BYTES
            HeapOwners.keep(state, peak.split(HeapJson.add(SAVE_METADATA_BYTES, references)))
        }
    }

    /** A snapshot shares the payload charge of the live record it was taken from, because both hold the same
     *  payloads, and takes its own shell out of the peak. One without a source, or whose record cannot cover it,
     *  takes its whole charge there. */
    private fun charge(snapshot: CodeModeRecordSnapshot, source: CodeModeRecord?, peak: HeapLease) {
        if (source != null && CodeModeHeap.share(source, snapshot, peak)) return
        snapshot.heapLease = peak.split(CodeModeHeap.retained(snapshot)).also { HeapOwners.keep(snapshot, it) }
    }

    fun encoding(cells: List<CodeModeRecordSnapshot>, expired: List<CodeModeExpiredSnapshot>): Encoding {
        val bytes = cells.fold(markerBytes(expired)) { total, cell -> HeapJson.add(total, CodeModeHeap.bytes(cell)) }
        return Encoding(reserve(bytes, ENCODING_PEAK_FACTOR))
    }

    fun full(state: CodeModePersistedState): Encoding = encoding(state.records, state.expired)

    private fun markerBytes(expired: List<CodeModeExpiredSnapshot>): Long =
        expired.fold(SAVE_METADATA_BYTES) { total, marker -> HeapJson.add(total, CodeModeHeap.bytes(marker)) }

    private fun reserve(bytes: Long, factor: Long): HeapLease =
        heap.reserve(HeapWeights.multiply(bytes, factor)) ?: throw HeapCapacityException()
}
