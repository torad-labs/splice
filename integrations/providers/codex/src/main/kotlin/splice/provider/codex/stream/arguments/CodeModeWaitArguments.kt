package splice.provider.codex.stream.arguments

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.provider.codex.stream.CodeModeNativeInvocation
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.json.CodeModeArgumentJson
import java.math.BigInteger

/** ExecWaitArgs' serde visitor, including tuple form, duplicate fields and nullable max_tokens. */
internal object CodeModeWaitArguments {
    fun read(source: String): CodeModeNativeInvocation {
        val values = CodeModeStructArguments(
            "ExecWaitArgs",
            WAIT_FIELDS,
            WAIT_CELL_ID,
        ).read(source, ::field)
        val yieldTime = values[WAIT_YIELD_FIELD] as? BigInteger ?: BigInteger.valueOf(
            WAIT_YIELD_MS,
        )
        val tokens = values[WAIT_TOKEN_FIELD] as? BigInteger ?: BigInteger.valueOf(
            WAIT_OUTPUT_TOKENS,
        )
        return CodeModeNativeInvocation(
            options(yieldTime, tokens),
            checkNotNull(values[WAIT_CELL_ID] as? String),
            values[CODE_MODE_WAIT_ARGUMENTS_TERMINATE] == true,
        )
    }

    private fun field(reader: CodeModeArgumentJson, field: String): Any? = when (field) {
        WAIT_CELL_ID -> string(reader)
        CODE_MODE_WAIT_ARGUMENTS_TERMINATE -> boolean(reader)
        WAIT_YIELD_FIELD, WAIT_TOKEN_FIELD -> {
            val expected = if (field == WAIT_YIELD_FIELD) "u64" else "usize"
            val value = reader.value(expected)
            if (value == JsonNull && field == WAIT_TOKEN_FIELD) {
                null
            } else {
                reader.unsigned(
                    value,
                    expected,
                )
            }
        }
        else -> error("Not an ExecWaitArgs field")
    }

    private fun string(reader: CodeModeArgumentJson): String {
        val value = reader.value("a string")
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) reader.invalid(value, "a string")
        return primitive.content
    }

    private fun boolean(reader: CodeModeArgumentJson): Boolean {
        val value = reader.value("a boolean")
        val primitive = value as? JsonPrimitive
        return primitive?.takeUnless { it.isString }?.let(JsonScalars::str)?.toBooleanStrictOrNull()
            ?: reader.invalid(value, "a boolean")
    }

    private fun options(yieldTime: BigInteger, tokens: BigInteger): NativeCodeModeOptions {
        val maximum = BigInteger.valueOf(Long.MAX_VALUE)
        return NativeCodeModeOptions(
            yieldTime.min(maximum).toLong(),
            tokens.min(maximum).toLong(),
            yieldTime.takeIf { it > maximum },
            tokens.takeIf { it > maximum },
        )
    }
}

private val WAIT_FIELDS =
    listOf(
        WAIT_CELL_ID,
        WAIT_YIELD_FIELD,
        WAIT_TOKEN_FIELD,
        CODE_MODE_WAIT_ARGUMENTS_TERMINATE,
    )
private const val WAIT_YIELD_MS = 10_000L
private const val WAIT_OUTPUT_TOKENS = 10_000L

private const val WAIT_CELL_ID = "cell_id"
private const val WAIT_YIELD_FIELD = "yield_time_ms"
private const val WAIT_TOKEN_FIELD = "max_tokens"
private const val CODE_MODE_WAIT_ARGUMENTS_TERMINATE = "terminate"
