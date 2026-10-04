// NEW: code-mode records and their durable/retry owners spend the daemon's shared byte ledger.
package splice.provider.codex.state

import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.upstream.memory.JvmHeap

// why: scalar fields, mutable collections, source-state metadata and bounded transition bookkeeping.
private const val RECORD_METADATA_BYTES = 4096L

// why: aggregate list references and their backing-array slack survive independently of each snapshot.
private const val STATE_ENTRY_BYTES = 128L

/** Weights include retained payloads and collection growth, not encoded retention-policy bytes. */
internal object CodeModeHeap {
    fun bytes(record: CodeModeRecord): Long = RECORD_METADATA_BYTES + HeapJson.bytes(record.outer) +
        HeapJson.text(record.source) + record.pending.sumOf(::bytes) + record.accepted.heapBytes() +
        HeapJson.text(record.output.orEmpty()) + HeapJson.text(record.error.orEmpty()) +
        record.nativeSegments.sumOf(::bytes) + record.continuity.sumOf(HeapJson::bytes) +
        record.continuityReplay.sumOf(::bytes) + record.issued.sumOf { step ->
            HeapJson.text(step.requestDigest) + step.calls.sumOf(::bytes)
        }

    fun bytes(record: CodeModeRecordSnapshot): Long = RECORD_METADATA_BYTES + HeapJson.bytes(record.outer) +
        HeapJson.text(record.source) + record.pending.sumOf(::bytes) + record.results.entries.sumOf { (id, result) ->
            HeapJson.text(id) + HeapJson.text(result.output) + result.media.orEmpty().sumOf(HeapJson::bytes)
        } + HeapJson.text(record.output.orEmpty()) + HeapJson.text(record.error.orEmpty()) +
        record.nativeSegments.sumOf(::bytes) + record.continuity.sumOf(HeapJson::bytes) +
        record.continuityReplay.sumOf(::bytes) + record.issued.sumOf { step ->
            HeapJson.text(step.requestDigest) + step.calls.sumOf(::bytes)
        }

    fun bytes(call: CodeModePending): Long = HeapJson.text(call.runtimeId) + HeapJson.text(call.clientId) +
        HeapJson.text(call.name) + HeapJson.bytes(call.arguments)

    fun bytes(segment: CodeModeNativeSegment): Long = RECORD_METADATA_BYTES + segment.items.sumOf(HeapJson::bytes)

    fun bytes(marker: CodeModeExpiredSnapshot): Long = RECORD_METADATA_BYTES + HeapJson.text(marker.key) +
        HeapJson.text(marker.lastDigest) + marker.resultIds.sumOf(HeapJson::text)

    fun own(record: CodeModeRecord, heap: HeapBudget = JvmHeap.budget, minimumBytes: Long = bytes(record)) {
        val lease = record.heapLease
        if (lease == null) {
            record.heapLease = HeapOwners.charge(record, heap, minimumBytes)
        } else if (!lease.resize(maxOf(lease.bytes, minimumBytes))) {
            throw HeapCapacityException()
        }
    }

    fun own(record: CodeModeRecordSnapshot, heap: HeapBudget = JvmHeap.budget) {
        if (record.heapLease == null) record.heapLease = HeapOwners.charge(record, heap, bytes(record))
    }

    fun own(marker: CodeModeExpiredSnapshot, heap: HeapBudget = JvmHeap.budget) {
        if (marker.heapLease == null) marker.heapLease = HeapOwners.charge(marker, heap, bytes(marker))
    }

    /** Adopt the returned aggregate and each independently escapable snapshot before closing decode stages. */
    fun ownState(state: CodeModePersistedState, heap: HeapBudget): CodeModePersistedState {
        state.records.forEach { own(it, heap) }
        state.expired.forEach { own(it, heap) }
        HeapOwners.charge(
            state,
            heap,
            RECORD_METADATA_BYTES + (state.records.size + state.expired.size) * STATE_ENTRY_BYTES,
        )
        return state
    }

    /** Reserve before mutation. High-water collection capacity is never refunded by a logical clear. */
    fun grow(record: CodeModeRecord, bytes: Long) {
        own(record)
        val lease = checkNotNull(record.heapLease)
        val needed = HeapJson.add(lease.bytes, bytes)
        if (!lease.resize(needed)) throw HeapCapacityException()
    }
}
