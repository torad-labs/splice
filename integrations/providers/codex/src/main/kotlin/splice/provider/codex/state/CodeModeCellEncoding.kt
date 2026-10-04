// NEW: generated record fields are compared before changed values are encoded.
package splice.provider.codex.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeRecordSnapshot

/** One cell's changed encodings and measured field sizes, without visiting unchanged heavy trees. */
internal class CodeModeCellEncoding(
    val snapshot: CodeModeRecordSnapshot,
    prior: CodeModeRecordSnapshot?,
    private val json: Json,
) {
    private val values = RecordFields(json).of(snapshot)
    private val before = prior?.let { RecordFields(json).of(it) }.orEmpty()
    private val encoded = linkedMapOf<String, String>()

    init {
        values.forEach { (name, field) ->
            if (field.changed(before[name])) encoded[name] = field.text.encode()
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

    /** Generated serialization stops at each top-level field, without visiting heavy child trees. */
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
