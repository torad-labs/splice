package splice.provider.codex.stream

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.stream.arguments.CodeModeWaitArguments
import splice.upstream.codemode.CodeModeText
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException
import java.math.BigInteger

/** Native observation controls are metadata, never a second executable program. */
internal data class CodeModeNativeInvocation(
    val options: NativeCodeModeOptions,
    val cellId: String? = null,
    val terminate: Boolean = false,
    val source: String? = null,
)

/** A model argument error is returned as that tool's output; it never loses or reruns source. */
internal class CodeModeArgumentException(message: String, cause: IllegalArgumentException) :
    IllegalArgumentException(message, cause)

private fun interface CodeModeInvocationParse {
    fun read(): CodeModeNativeInvocation
}

internal object CodeModeNativeInvocations {
    private val execFields =
        setOf(EXEC_YIELD_FIELD, EXEC_TOKEN_FIELD)

    fun exec(
        source: String,
        defaultYieldTimeMs: Long = NATIVE_DEFAULT_EXEC_YIELD_MS,
    ): CodeModeNativeInvocation = parsed {
        require(!CodeModeText.isBlank(source)) {
            "exec expects raw JavaScript source text (non-empty). Provide JS only, optionally with first-line " +
                "`// @exec: {\"yield_time_ms\": 10000, \"max_output_tokens\": 1000}`."
        }
        val lines = source.split('\n', limit = 2)
        val first = CodeModeText.trimStart(lines.first())
        if (!first.startsWith("// @exec:")) {
            return@parsed CodeModeNativeInvocation(
                NativeCodeModeOptions(yieldTimeMs = defaultYieldTimeMs),
                source = source,
            )
        }
        val code = lines.getOrNull(1)
        require(code != null && !CodeModeText.isBlank(code)) {
            "exec pragma must be followed by JavaScript source on subsequent lines"
        }
        val header = CodeModeText.trim(first.removePrefix("// @exec:"))
        val options = requireNotNull(pragma(header) as? JsonObject) {
            "exec pragma must be a JSON object with supported fields `yield_time_ms` and `max_output_tokens`"
        }
        val unknown = options.keys.firstOrNull { it !in execFields }
        require(unknown == null) {
            "exec pragma only supports `yield_time_ms` and `max_output_tokens`; got `$unknown`"
        }
        // The release Value map preserves field insertion order before either integer bound is checked.
        val tokens = unsigned(options, EXEC_TOKEN_FIELD)
        val yieldTime = unsigned(options, EXEC_YIELD_FIELD)
        CodeModeNativeInvocation(
            NativeCodeModeOptions(
                integer(yieldTime, EXEC_YIELD_FIELD, defaultYieldTimeMs),
                integer(tokens, EXEC_TOKEN_FIELD, NATIVE_DEFAULT_OUTPUT_TOKENS),
            ),
            source = code,
        )
    }

    fun wait(arguments: String): CodeModeNativeInvocation = parsed { CodeModeWaitArguments.read(arguments) }

    private fun unsigned(value: JsonObject, field: String): BigInteger? {
        val supplied = value[field]?.takeUnless { it is JsonNull } ?: return null
        val number = try {
            CodeModeJson.unsigned(supplied, if (field == EXEC_YIELD_FIELD) "u64" else "usize")
        } catch (error: CodeModeJsonException) {
            throw IllegalArgumentException(
                "exec pragma fields `yield_time_ms` and `max_output_tokens` must be non-negative safe integers: " +
                    error.diagnostic,
                error,
            )
        }
        return number
    }

    private fun integer(number: BigInteger?, field: String, default: Long): Long {
        if (number == null) return default
        require(number <= BigInteger.valueOf(MAX_NATIVE_SAFE_INTEGER)) {
            "exec pragma field `$field` must be a non-negative safe integer"
        }
        return number.toLong()
    }

    private fun pragma(header: String): kotlinx.serialization.json.JsonElement? {
        if (header.isEmpty()) return null
        return try {
            CodeModeJson.parse(header)
        } catch (error: CodeModeJsonException) {
            throw IllegalArgumentException(
                "exec pragma must be valid JSON with supported fields `yield_time_ms` and `max_output_tokens`: " +
                    error.diagnostic,
                error,
            )
        }
    }

    private fun parsed(block: CodeModeInvocationParse): CodeModeNativeInvocation = try {
        block.read()
    } catch (error: IllegalArgumentException) {
        throw CodeModeArgumentException(error.message.orEmpty(), error)
    }
}

private const val NATIVE_DEFAULT_EXEC_YIELD_MS = splice.upstream.codemode.CodeModeManual.DEFAULT_EXEC_YIELD_TIME_MS

// Pinned native formatter defaults to ten thousand output tokens.
private const val NATIVE_DEFAULT_OUTPUT_TOKENS = 10_000L

// JavaScript integer values must remain exactly representable across JSON and native parsing.
private const val MAX_NATIVE_SAFE_INTEGER = 9_007_199_254_740_991L

private const val EXEC_YIELD_FIELD = "yield_time_ms"
private const val EXEC_TOKEN_FIELD = "max_output_tokens"
