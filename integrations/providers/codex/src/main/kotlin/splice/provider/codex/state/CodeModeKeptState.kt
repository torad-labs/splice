// NEW: the durable cell index is updated only after a forced write, never rebuilt for a changed-cell append.
package splice.provider.codex.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot

internal class CodeModeKeptState(state: CodeModePersistedState) {
    private val byId: MutableMap<String, CodeModeRecordSnapshot> =
        state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
    val records: Map<String, CodeModeRecordSnapshot> get() = byId
    val expired = state.expired

    /** The encoded bytes of [records], kept as cells land so a cell write never re-measures its conversation.
     *  Every kept record was measured: the store measures each one it reads or writes whole. */
    var liveBytes: Long = byId.values.sumOf { it.retainedBytes ?: 0L }
        private set

    /** Records [written] cells after their forced write. */
    fun put(written: List<CodeModeRecordSnapshot>) {
        liveBytes = liveBytesWith(written)
        written.forEach { byId[it.id] = it }
    }

    /** [liveBytes] once [changed] cells replace the ones of the same id. */
    fun liveBytesWith(changed: List<CodeModeRecordSnapshot>): Long =
        liveBytes + changed.sumOf { (it.retainedBytes ?: 0L) - (byId[it.id]?.retainedBytes ?: 0L) }

    /** Failed saves keep live references, so a rolled-back acceptance is never resurrected on retry. */
    val dirty: MutableMap<String, CodeModeRecord> = linkedMapOf()
    private val version = state.version

    /** Full materialization is for load, deletion compaction and missing-file recovery only. */
    fun snapshot(): CodeModePersistedState =
        CodeModePersistedState(version, records.values.toList(), expired)

    fun withCells(cells: List<CodeModeRecordSnapshot>): CodeModePersistedState {
        val next = LinkedHashMap(records)
        cells.forEach { next[it.id] = it }
        return CodeModePersistedState(version, next.values.toList(), expired)
    }

    /** Serializer-generated field visits compare values before any field is encoded. */
    class CellEncoding(
        val snapshot: CodeModeRecordSnapshot,
        prior: CodeModeRecordSnapshot?,
        private val json: Json,
    ) {
        private val values = RecordFields(json).of(snapshot)
        private val before = prior?.let { RecordFields(json).of(it) }.orEmpty()
        private val encoded = linkedMapOf<String, String>()

        init {
            values.forEach { (name, field) ->
                if (field.changed(before[name])) {
                    encoded[name] = field.text.encode()
                }
            }
            val sizes = prior?.encodedFieldBytes.orEmpty().toMutableMap()
            values.keys.forEach { name ->
                val text = encoded[name]
                if (text != null) sizes[name] = CodeModeStateText(text).bytes
                if (sizes[name] == null) sizes[name] = CodeModeStateText(encoding(name)).bytes
            }
            snapshot.encodedFieldBytes = sizes
            snapshot.retainedBytes = 2L + (values.size - 1).coerceAtLeast(0) + values.keys.sumOf { name ->
                JsonWire.byteSize(JsonPrimitive(name)) + 1L + checkNotNull(sizes[name])
            }
        }

        fun patch(): String = buildString {
            append("{\"id\":")
            append(encoded["id"] ?: JsonWire.string(JsonPrimitive(snapshot.id)))
            append(",\"fields\":")
            append(fields(encoded.keys))
            append('}')
        }

        fun full(): String = fields(values.keys)

        private fun encoding(name: String): String =
            encoded.getOrPut(name) { checkNotNull(values[name]).text.encode() }

        private fun fields(names: Collection<String>): String = buildString {
            append('{')
            names.forEachIndexed { index, name ->
                if (index != 0) append(',')
                append(JsonPrimitive(name))
                append(':')
                append(encoding(name))
            }
            append('}')
        }
    }

    /** Assembles entries from each field's single encoding, including full recovery checkpoints. */
    class Encoding(private val json: Json) {
        fun patch(
            key: String,
            cells: List<CellEncoding>,
            expired: List<CodeModeExpiredSnapshot>,
        ): String = buildString {
            append("{\"key\":")
            append(JsonPrimitive(key))
            append(",\"patches\":[")
            cells.forEachIndexed { index, cell ->
                if (index != 0) append(',')
                append(cell.patch())
            }
            append("],\"expired\":")
            append(json.encodeToString(expired))
            append('}')
        }

        fun checkpoint(state: CodeModePersistedState, encoded: List<CellEncoding>): String = buildString {
            val prepared = encoded.associateBy { it.snapshot.id }
            append("{\"version\":")
            append(state.version)
            append(",\"records\":[")
            state.records.forEachIndexed { index, record ->
                if (index != 0) append(',')
                append((prepared[record.id] ?: CellEncoding(record, record, json)).full())
            }
            append("],\"expired\":")
            append(json.encodeToString(state.expired))
            append('}')
        }
    }

    private fun interface FieldText {
        fun encode(): String
    }

    private data class Field(val value: Any?, val text: FieldText) {
        fun changed(previous: Field?): Boolean {
            if (previous == null) return true
            if (value === previous.value) return false
            return value != previous.value
        }
    }

    /** Stops generated serialization at each top-level value, without visiting heavy child trees. */
    @OptIn(ExperimentalSerializationApi::class)
    private class RecordFields(private val json: Json) : AbstractEncoder() {
        override val serializersModule: SerializersModule get() = json.serializersModule
        private val values = linkedMapOf<String, Field>()
        private var name = ""

        fun of(record: CodeModeRecordSnapshot): Map<String, Field> {
            CodeModeRecordSnapshot.serializer().serialize(this, record)
            return values
        }

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            name = descriptor.getElementName(index)
            return true
        }

        override fun encodeNull() {
            values[name] = Field(null, FieldText { "null" })
        }

        override fun encodeValue(value: Any) {
            val primitive = when (value) {
                is String -> JsonPrimitive(value)
                is Char -> JsonPrimitive(value.toString())
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> error("unsupported code-mode record primitive")
            }
            values[name] = Field(value, FieldText { json.encodeToString(JsonElement.serializer(), primitive) })
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            values[name] = Field(value, FieldText { json.encodeToString(serializer, value) })
        }
    }
}

// UTF-8 uses one byte below U+0080, two below U+0800, and three for the remaining BMP scalars.
private const val ASCII_CHAR_LIMIT = 0x80

// UTF-8's two-byte form represents scalar values below U+0800.
private const val TWO_BYTE_CHAR_LIMIT = 0x800

// A non-surrogate BMP scalar at or above U+0800 takes three UTF-8 bytes.
private const val BMP_CHAR_BYTES = 3

// A paired UTF-16 surrogate represents one supplementary scalar, encoded as four UTF-8 bytes.
private const val SURROGATE_PAIR_BYTES = 4

/** Encoded JSON and its UTF-8 size, without allocating a byte copy just to measure it. */
internal class CodeModeStateText(val text: String) {
    val bytes: Long = count()

    private fun count(): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char.code < ASCII_CHAR_LIMIT -> 1
                char.code < TWO_BYTE_CHAR_LIMIT -> 2
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    SURROGATE_PAIR_BYTES
                }
                char.isSurrogate() -> 1 // JVM UTF-8 replaces an unpaired surrogate with '?'.
                else -> BMP_CHAR_BYTES
            }
        }
        return bytes
    }
}
