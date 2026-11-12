package splice.provider.codex.stream.arguments

import splice.upstream.codemode.json.CodeModeArgumentJson
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException

/** The field's declared Rust type chooses which serde reader operation consumes it. */
internal fun interface CodeModeStructField {
    fun read(reader: CodeModeArgumentJson, field: String): Any?
}

/** Shared serde struct visitor behavior; individual field visitors retain their exact source types. */
internal class CodeModeStructArguments(
    private val name: String,
    private val fields: List<String>,
    private val required: String,
) {
    fun read(source: String, field: CodeModeStructField): Map<String, Any?> {
        return try {
            CodeModeJson.read(source) { reader ->
                val values = LinkedHashMap<String, Any?>()
                val sequence = reader.peek() == '['
                when (reader.peek()) {
                    '{' -> objectFields(reader, field, values)
                    '[' -> reader.sequence(
                        fields.size,
                    ) { index -> values[fields[index]] = field.read(reader, fields[index]) }
                    else -> reader.invalid(reader.value("struct $name"), "struct $name")
                }
                if (!values.containsKey(required)) {
                    reader.fail(
                        if (sequence) {
                            "invalid length 0, expected struct $name with ${fields.size} elements"
                        } else {
                            "missing field `$required`"
                        },
                    )
                }
                values
            }
        } catch (error: CodeModeJsonException) {
            throw IllegalArgumentException("failed to parse function arguments: ${error.diagnostic}", error)
        }
    }
    private fun objectFields(
        reader: CodeModeArgumentJson,
        field: CodeModeStructField,
        values: MutableMap<String, Any?>,
    ) {
        reader.objectFields(beforeField = { key ->
            if (key in fields && values.containsKey(key)) reader.visitorFailure("duplicate field `$key`")
        }) { key -> if (key in fields) values[key] = field.read(reader, key) else reader.ignoreValue() }
    }
}
