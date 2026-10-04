// NEW: code-mode records and their durable/retry owners spend the daemon's shared byte ledger.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.upstream.memory.JvmHeap
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import javax.management.JMException
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

// why: scalar fields, mutable collections, source-state metadata and bounded transition bookkeeping.
private const val RECORD_METADATA_BYTES = 4096L

// why: aggregate list references and their backing-array slack survive independently of each snapshot.
private const val STATE_ENTRY_BYTES = 128L

// why: a snapshot copies each result and pending call into an object and a map node of its own.
private const val SNAPSHOT_ENTRY_BYTES = 64L

// why: a snapshot copies each issued step and native segment it shares into a list slot of its own.
private const val SNAPSHOT_SLOT_BYTES = 8L

// why: a String and the JSON primitive that holds it, around the array that stores its characters.
private const val TEXT_OWNER_BYTES = 64L

// why: a byte array's header in front of the characters it stores.
private const val ARRAY_HEADER_BYTES = 16L

// why: every heap object is padded to a multiple of eight bytes.
private const val OBJECT_ALIGNMENT = 8L

// why: the highest code point a compact string stores in one byte.
private const val LATIN1_MAX = 0xFF

/** How the running JVM stores text. Compact strings keep Latin-1 text at one byte a character, and G1 stores an array
 *  of half a region or more in whole regions of its own. The daemon runs G1 at -Xmx2048m (DaemonLaunch's
 *  DEFAULT_JVM_OPTS), so its regions are 1 MiB and any string of 512 KiB or more takes whole ones. The options are read
 *  from the HotSpot diagnostic bean by its object name, the string form a class literal would otherwise name. A JVM
 *  without that bean is weighed at two bytes a character with no regions. */
private object CodeModeTextLayout {
    private val server = ManagementFactory.getPlatformMBeanServer()
    private val diagnostic = ObjectName("com.sun.management:type=HotSpotDiagnostic")
    val compactStrings: Boolean = option("CompactStrings") == "true"
    val regionBytes: Long = if (option("UseG1GC") == "true") option("G1HeapRegionSize")?.toLongOrNull() ?: 0L else 0L

    private fun option(name: String): String? = try {
        val option = server.invoke(diagnostic, "getVMOption", arrayOf(name), arrayOf("java.lang.String"))
        (option as? CompositeData)?.get("value") as? String
    } catch (_: JMException) {
        null
    }
}

/** How a payload is weighed. A save or decode stage reserves its peak on UTF-16 text, the estimate its factor was
 *  sized on, and releases it. A retained graph, and each growth of one, is charged what it occupies. */
internal enum class CodeModeWeight {
    PEAK {
        override fun text(value: String): Long = HeapJson.text(value)

        override fun json(value: JsonElement): Long = HeapJson.bytes(value)
    },
    STORED {
        override fun text(value: String): Long {
            val width = if (CodeModeTextLayout.compactStrings && value.all { it.code <= LATIN1_MAX }) 1L else 2L
            return HeapJson.add(TEXT_OWNER_BYTES, array(HeapJson.add(ARRAY_HEADER_BYTES, value.length * width)))
        }

        /** HeapJson's objects, entries and references, with each text it holds at its stored width. */
        override fun json(value: JsonElement): Long {
            val structure = HeapJson.bytes(value)
            val widths = widths(value)
            return if (widths >= 0) HeapJson.add(structure, widths) else structure + widths
        }

        private fun widths(value: JsonElement): Long = when (value) {
            is JsonPrimitive -> text(value.content) - HeapJson.text(value.content)
            is JsonArray -> value.sumOf(::widths)
            is JsonObject -> value.entries.sumOf { (key, child) -> text(key) - HeapJson.text(key) + widths(child) }
        }

        private fun array(bytes: Long): Long {
            val aligned = padded(bytes, OBJECT_ALIGNMENT)
            val region = CodeModeTextLayout.regionBytes
            return if (region > 0 && aligned >= region / 2) padded(aligned, region) else aligned
        }

        private fun padded(bytes: Long, unit: Long): Long = HeapJson.add(bytes, unit - 1) / unit * unit
    },
    ;

    abstract fun text(value: String): Long

    abstract fun json(value: JsonElement): Long

    fun record(record: CodeModeRecord): Long = RECORD_METADATA_BYTES +
        json(record.outer) + text(record.source) + record.pending.sumOf(::call) +
        record.results.entries.sumOf { (id, result) ->
            text(id) + text(result.output) + record.accepted.media(id).orEmpty().sumOf(::json)
        } + text(record.output.orEmpty()) + text(record.error.orEmpty()) +
        record.nativeSegments.sumOf(::segment) + record.continuity.sumOf(::json) +
        record.continuityReplay.sumOf(::segment) + record.issued.sumOf { step ->
            text(step.requestDigest) + step.calls.sumOf(::call)
        }

    fun snapshot(record: CodeModeRecordSnapshot): Long = RECORD_METADATA_BYTES +
        json(record.outer) + text(record.source) + record.pending.sumOf(::call) +
        record.results.entries.sumOf { (id, result) ->
            text(id) + text(result.output) + result.media.orEmpty().sumOf(::json)
        } + text(record.output.orEmpty()) + text(record.error.orEmpty()) +
        record.nativeSegments.sumOf(::segment) + record.continuity.sumOf(::json) +
        record.continuityReplay.sumOf(::segment) + record.issued.sumOf { step ->
            text(step.requestDigest) + step.calls.sumOf(::call)
        }

    fun call(call: CodeModePending): Long =
        text(call.runtimeId) + text(call.clientId) + text(call.name) + json(call.arguments)

    fun segment(segment: CodeModeNativeSegment): Long = RECORD_METADATA_BYTES + segment.items.sumOf(::json)
}

/** What a snapshot still holds, at stored width, of the payloads a mutation takes off its record. */
internal fun interface CodeModeKeptPayload {
    operator fun invoke(snapshot: CodeModeRecordSnapshot): Long
}

/** Peak weights size the reservations of save and decode stages. Retained weights are what a record or snapshot
 *  holds, and a payload a record and its snapshots hold together is charged once, on the lease they share. */
internal object CodeModeHeap {
    fun bytes(record: CodeModeRecord): Long = CodeModeWeight.PEAK.record(record)

    fun bytes(record: CodeModeRecordSnapshot): Long = CodeModeWeight.PEAK.snapshot(record)

    fun bytes(segment: CodeModeNativeSegment): Long = CodeModeWeight.PEAK.segment(segment)

    fun bytes(marker: CodeModeExpiredSnapshot): Long = RECORD_METADATA_BYTES + HeapJson.text(marker.key) +
        HeapJson.text(marker.lastDigest) + marker.resultIds.sumOf(HeapJson::text)

    fun retained(record: CodeModeRecordSnapshot): Long = CodeModeWeight.STORED.snapshot(record)

    /** Charge [record] at least what its graph occupies, with the [inherited] native payload it will hold once a
     *  checkpoint it survives publishes it as a root. */
    fun own(
        record: CodeModeRecord,
        heap: HeapBudget = JvmHeap.budget,
        inherited: List<CodeModeNativeSegment> = emptyList(),
    ) {
        val minimumBytes = inherited.fold(CodeModeWeight.STORED.record(record)) { total, segment ->
            HeapJson.add(total, CodeModeWeight.STORED.segment(segment))
        }
        if (record.heapBudget == null) record.heapBudget = heap
        val lease = record.heapLease
        if (lease == null) {
            record.heapLease = HeapOwners.charge(record, heap, minimumBytes)
        } else if (!lease.resize(maxOf(lease.bytes, minimumBytes))) {
            throw HeapCapacityException()
        }
    }

    fun own(record: CodeModeRecordSnapshot, heap: HeapBudget = JvmHeap.budget) {
        if (record.heapLease == null) record.heapLease = HeapOwners.charge(record, heap, retained(record))
    }

    fun own(marker: CodeModeExpiredSnapshot, heap: HeapBudget = JvmHeap.budget) {
        if (marker.heapLease == null) marker.heapLease = HeapOwners.charge(marker, heap, bytes(marker))
    }

    /** A snapshot taken of [record] holds the record's own payload objects, so it shares the record's charge rather
     *  than taking a second one, and the shared charge covers the larger graph. The snapshot's own structures around
     *  those payloads, its object and its copied maps and lists, are split out of [peak] and refunded with it.
     *  False leaves the snapshot uncharged. */
    fun share(record: CodeModeRecord, snapshot: CodeModeRecordSnapshot, peak: HeapLease): Boolean {
        val lease = record.heapLease ?: return false
        if (!lease.resize(maxOf(lease.bytes, retained(snapshot)))) return false
        val shell = RECORD_METADATA_BYTES + (snapshot.results.size + snapshot.pending.size) * SNAPSHOT_ENTRY_BYTES +
            (snapshot.issued.size + snapshot.nativeSegments.size) * SNAPSHOT_SLOT_BYTES
        HeapOwners.keep(snapshot, peak.split(shell))
        snapshot.heapLease = lease.share().also { HeapOwners.keep(snapshot, it) }
        record.heapSnapshots += WeakReference(snapshot)
        return true
    }

    /** A record restored from [saved] holds the snapshot's own payload objects, so it shares the snapshot's charge,
     *  grown by the record's own shell. A record whose shell does not fit takes no charge here, and [own] then
     *  charges it whole. */
    fun adopt(record: CodeModeRecord, saved: CodeModeRecordSnapshot) {
        val lease = saved.heapLease ?: return
        if (!lease.resize(HeapJson.add(lease.bytes, RECORD_METADATA_BYTES))) return
        record.heapLease = lease.share().also { HeapOwners.keep(record, it) }
        record.heapSnapshots += WeakReference(saved)
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

    /** Reserve before mutation, [bytes] weighed at stored width. High-water collection capacity is never refunded by a
     *  logical clear. The record's charge grows by the difference, and a payload this mutation takes off the record
     *  stays live while a snapshot sharing the charge still holds it: [kept] weighs what each such snapshot holds, and
     *  that snapshot is charged it until it goes. All of it is reserved before any of it is kept. */
    fun grow(record: CodeModeRecord, bytes: Long, kept: CodeModeKeptPayload = CodeModeKeptPayload { 0L }) {
        if (record.heapLease == null) own(record)
        val lease = checkNotNull(record.heapLease)
        val held = holders(record, kept)
        if (!lease.resize(HeapJson.add(lease.bytes, bytes))) {
            held.values.forEach(HeapLease::close)
            throw HeapCapacityException()
        }
        held.forEach { (snapshot, charge) -> HeapOwners.keep(snapshot, charge) }
    }

    /** A reservation for each live snapshot of [record] that still holds payload [kept] weighs above zero. */
    private fun holders(
        record: CodeModeRecord,
        kept: CodeModeKeptPayload,
    ): Map<CodeModeRecordSnapshot, HeapLease> {
        record.heapSnapshots.removeAll { it.get() == null }
        val heap = record.heapBudget ?: JvmHeap.budget
        val held = IdentityHashMap<CodeModeRecordSnapshot, HeapLease>()
        record.heapSnapshots.mapNotNull(WeakReference<CodeModeRecordSnapshot>::get).forEach { snapshot ->
            val bytes = kept(snapshot)
            if (bytes > 0 && snapshot !in held) {
                held[snapshot] = heap.reserve(bytes) ?: run {
                    held.values.forEach(HeapLease::close)
                    throw HeapCapacityException()
                }
            }
        }
        return held
    }
}
