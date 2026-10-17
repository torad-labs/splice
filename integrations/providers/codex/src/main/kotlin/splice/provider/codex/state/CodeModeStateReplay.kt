// NEW: journal replay retains charged current cells, never all superseded entry graphs.
package splice.provider.codex.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapCategory
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.util.JsonScalars
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot

// why: two keyed maps, entry references and their backing-table slack coexist with each raw cell.
private const val REPLAY_ENTRY_BYTES = 256L

// why: validation and final decoding coexist with a raw graph and its replacement containers.
private const val REPLAY_DECODE_FACTOR = 4L
private const val REPLAY_RECORDS = "records"
private const val REPLAY_EXPIRED = "expired"

/** All raw trees are confined here. Escaped snapshots adopt their own charge before these leases close. */
@OptIn(ExperimentalSerializationApi::class)
internal class CodeModeStateReplay(
    checkpoint: JsonObject,
    private val json: Json,
    private val heap: HeapReservations,
) : AutoCloseable {
    private val records = linkedMapOf<String, JsonObject>()
    private val charges = linkedMapOf<String, HeapLease>()
    private var expired = JsonArray(emptyList())
    private var expiry: HeapLease? = null
    private val metadata = JsonObject(checkpoint - REPLAY_RECORDS - REPLAY_EXPIRED)
    private val base = heap.reserve(HeapJson.bytes(metadata) + REPLAY_ENTRY_BYTES, HeapCategory.REPLAY)
        ?: throw HeapCapacityException()
    private val key: String
    private val descriptor = CodeModeRecordSnapshot.serializer().descriptor
    private val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

    init {
        var opened = false
        try {
            key = requireNotNull(
                (checkpoint[REPLAY_RECORDS]?.jsonArray.orEmpty() + checkpoint[REPLAY_EXPIRED]?.jsonArray.orEmpty())
                    .map { checkNotNull(JsonScalars.str(it.jsonObject["key"])) }.distinct().singleOrNull(),
            ) { "code-mode checkpoint has no unique conversation key" }
            val validated = json.decodeFromJsonElement<CodeModePersistedState>(checkpoint)
            checkpoint[REPLAY_RECORDS]?.jsonArray.orEmpty().forEach { cell ->
                val value = cell.jsonObject
                replace(checkNotNull(JsonScalars.str(value["id"])), value)
            }
            require(validated.records.size == records.size) { "code-mode checkpoint repeats cell identities" }
            replaceExpiry(checkpoint[REPLAY_EXPIRED]?.jsonArray ?: JsonArray(emptyList()))
            opened = true
        } finally {
            if (!opened) close()
        }
    }

    fun apply(change: JsonObject) {
        if ("patches" in change) patch(change) else delta(change)
        replaceExpiry(change.getValue(REPLAY_EXPIRED).jsonArray)
    }

    private fun patch(change: JsonObject) {
        val patch = json.decodeFromJsonElement<CodeModeStatePatch>(change)
        require(patch.key == key && patch.expired.all { it.key == key }) {
            "code-mode journal crosses conversation keys"
        }
        patch.patches.forEach { cell ->
            val before = records[cell.id]
            require(before != null || cell.fields.keys.containsAll(fields)) {
                "code-mode journal patch has no complete base cell"
            }
            patched(cell.id, before, cell.fields)
        }
    }

    private fun patched(id: String, before: JsonObject?, fields: JsonObject) {
        val bytes = HeapJson.add(before?.let(HeapJson::bytes) ?: 0L, HeapJson.bytes(fields))
        val peak = heap.reserve(
            HeapWeights.multiply(bytes + REPLAY_ENTRY_BYTES, REPLAY_DECODE_FACTOR),
            HeapCategory.REPLAY,
        ) ?: throw HeapCapacityException()
        peak.use {
            val next = JsonObject(before.orEmpty() + fields)
            val restored = json.decodeFromJsonElement<CodeModeRecordSnapshot>(next)
            require(restored.id == id && restored.key == key) { "code-mode journal crosses cell identities" }
            replace(id, next)
        }
    }

    private fun delta(change: JsonObject) {
        val delta = json.decodeFromJsonElement<CodeModeStateDelta>(change)
        val sameRecords = delta.key == key && delta.records.all { it.key == key }
        require(sameRecords && delta.expired.all { it.key == key }) { "code-mode journal crosses conversation keys" }
        delta.removed.forEach { id ->
            val _ = records.remove(id)
            charges.remove(id)?.close(HeapRelease.EVICTED)
        }
        change.getValue(REPLAY_RECORDS).jsonArray.forEach { cell ->
            val value = cell.jsonObject
            replace(checkNotNull(JsonScalars.str(value["id"])), value)
        }
    }

    private fun replace(id: String, value: JsonObject) {
        val next = heap.reserve(HeapJson.bytes(value) + REPLAY_ENTRY_BYTES, HeapCategory.REPLAY)
            ?: throw HeapCapacityException()
        val prior = charges.put(id, next)
        records[id] = value
        prior?.close()
    }

    private fun replaceExpiry(value: JsonArray) {
        val next = heap.reserve(HeapJson.bytes(value) + REPLAY_ENTRY_BYTES, HeapCategory.REPLAY)
            ?: throw HeapCapacityException()
        val prior = expiry
        expired = value
        expiry = next
        prior?.close()
    }

    fun finish(): CodeModePersistedState {
        val referenceBytes = HeapJson.add(base.bytes, records.size.toLong() * REPLAY_ENTRY_BYTES)
        if (!base.resize(referenceBytes)) throw HeapCapacityException()
        val restored = ArrayList<CodeModeRecordSnapshot>(records.size)
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val (id, value) = iterator.next()
            restored += restoreRecord(value)
            // The independently owned snapshot now covers the payload shared with this raw cell.
            charges.remove(id)?.close()
            iterator.remove()
        }
        val version = json.decodeFromJsonElement<CodeModePersistedState>(metadata).version
        val state = CodeModePersistedState(version, restored, restoreExpiry())
        return CodeModeHeap.ownState(state, heap)
    }

    private fun restoreRecord(value: JsonObject): CodeModeRecordSnapshot {
        val bytes = HeapWeights.multiply(HeapJson.bytes(value) + REPLAY_ENTRY_BYTES, REPLAY_DECODE_FACTOR)
        val peak = heap.reserve(bytes, HeapCategory.REPLAY) ?: throw HeapCapacityException()
        return peak.use {
            val record = CodeModeNativeCopies.dropped(json.decodeFromJsonElement<CodeModeRecordSnapshot>(value))
            CodeModeHeap.own(record, heap)
            record
        }
    }

    private fun restoreExpiry(): List<CodeModeExpiredSnapshot> {
        val bytes = HeapWeights.multiply(HeapJson.bytes(expired) + REPLAY_ENTRY_BYTES, REPLAY_DECODE_FACTOR)
        val peak = heap.reserve(bytes, HeapCategory.REPLAY) ?: throw HeapCapacityException()
        return peak.use {
            val markers = json.decodeFromJsonElement<List<CodeModeExpiredSnapshot>>(expired)
            markers.forEach { CodeModeHeap.own(it, heap) }
            expiry?.close()
            expiry = null
            expired = JsonArray(emptyList())
            markers
        }
    }

    override fun close() {
        charges.values.forEach(HeapLease::close)
        charges.clear()
        records.clear()
        expiry?.close()
        base.close()
    }
}
